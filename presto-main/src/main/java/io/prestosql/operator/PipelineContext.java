/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.operator;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.stats.CounterStat;
import io.airlift.stats.Distribution;
import io.airlift.units.Duration;
import io.prestosql.Session;
import io.prestosql.execution.Lifespan;
import io.prestosql.execution.TaskId;
import io.prestosql.memory.QueryContextVisitor;
import io.prestosql.memory.context.LocalMemoryContext;
import io.prestosql.memory.context.MemoryTrackingContext;
import org.joda.time.DateTime;

import javax.annotation.concurrent.ThreadSafe;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.airlift.units.DataSize.succinctBytes;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.stream.Collectors.toList;

@ThreadSafe
public class PipelineContext
{
    private final TaskContext taskContext;
    private final Executor notificationExecutor;
    private final ScheduledExecutorService yieldExecutor;
    private final int pipelineId;

    private final boolean inputPipeline;
    private final boolean outputPipeline;
    private final boolean partitioned;

    private final List<DriverContext> drivers = new CopyOnWriteArrayList<>();

    private final AtomicInteger totalSplits = new AtomicInteger();
    private final AtomicInteger completedDrivers = new AtomicInteger();

    private final AtomicReference<DateTime> executionStartTime = new AtomicReference<>();
    private final AtomicReference<DateTime> lastExecutionStartTime = new AtomicReference<>();
    private final AtomicReference<DateTime> lastExecutionEndTime = new AtomicReference<>();

    private final Distribution queuedTime = new Distribution();
    private final Distribution elapsedTime = new Distribution();

    private final AtomicLong totalScheduledTime = new AtomicLong();
    private final AtomicLong totalCpuTime = new AtomicLong();
    private final AtomicLong totalBlockedTime = new AtomicLong();

    private final CounterStat physicalInputDataSize = new CounterStat();
    private final CounterStat physicalInputPositions = new CounterStat();

    private final CounterStat internalNetworkInputDataSize = new CounterStat();
    private final CounterStat internalNetworkInputPositions = new CounterStat();

    private final CounterStat rawInputDataSize = new CounterStat();
    private final CounterStat rawInputPositions = new CounterStat();

    private final CounterStat processedInputDataSize = new CounterStat();
    private final CounterStat processedInputPositions = new CounterStat();

    private final CounterStat outputDataSize = new CounterStat();
    private final CounterStat outputPositions = new CounterStat();

    private final AtomicLong physicalWrittenDataSize = new AtomicLong();

    private final ConcurrentMap<Integer, OperatorStats> operatorSummaries = new ConcurrentHashMap<>();

    private final MemoryTrackingContext pipelineMemoryContext;

    private final AtomicLong inputBlockedTime = new AtomicLong();
    private final AtomicLong outputBlockedTime = new AtomicLong();

    public PipelineContext(int pipelineId, TaskContext taskContext, Executor notificationExecutor, ScheduledExecutorService yieldExecutor, MemoryTrackingContext pipelineMemoryContext, boolean inputPipeline, boolean outputPipeline, boolean partitioned)
    {
        this.pipelineId = pipelineId;
        this.inputPipeline = inputPipeline;
        this.outputPipeline = outputPipeline;
        this.partitioned = partitioned;
        this.taskContext = requireNonNull(taskContext, "taskContext is null");
        this.notificationExecutor = requireNonNull(notificationExecutor, "notificationExecutor is null");
        this.yieldExecutor = requireNonNull(yieldExecutor, "yieldExecutor is null");
        this.pipelineMemoryContext = requireNonNull(pipelineMemoryContext, "pipelineMemoryContext is null");
        // Initialize the local memory contexts with the ExchangeOperator tag as ExchangeOperator will do the local memory allocations
        pipelineMemoryContext.initializeLocalMemoryContexts(ExchangeOperator.class.getSimpleName());
    }

    public TaskContext getTaskContext()
    {
        return taskContext;
    }

    public TaskId getTaskId()
    {
        return taskContext.getTaskId();
    }

    public int getPipelineId()
    {
        return pipelineId;
    }

    public boolean isInputPipeline()
    {
        return inputPipeline;
    }

    public boolean isOutputPipeline()
    {
        return outputPipeline;
    }

    public DriverContext addDriverContext()
    {
        return addDriverContext(Lifespan.taskWide(), 0);
    }

    public DriverContext addDriverContext(Lifespan lifespan, int driverId)
    {
        DriverContext driverContext = new DriverContext(
                this,
                notificationExecutor,
                yieldExecutor,
                pipelineMemoryContext.newMemoryTrackingContext(),
                lifespan,
                driverId);
        drivers.add(driverContext);
        return driverContext;
    }

    public Session getSession()
    {
        return taskContext.getSession();
    }

    public void splitsAdded(int count)
    {
        checkArgument(count >= 0);
        totalSplits.addAndGet(count);
    }

    public void driverFinished(DriverContext driverContext)
    {
        requireNonNull(driverContext, "driverContext is null");

        if (!drivers.remove(driverContext)) {
            throw new IllegalArgumentException("Unknown driver " + driverContext);
        }

        // always update last execution end time
        lastExecutionEndTime.set(DateTime.now());

        DriverStats driverStats = driverContext.getDriverStats();

        completedDrivers.getAndIncrement();

        queuedTime.add(driverStats.getQueuedTime().roundTo(NANOSECONDS));
        elapsedTime.add(driverStats.getElapsedTime().roundTo(NANOSECONDS));

        totalScheduledTime.getAndAdd(driverStats.getTotalScheduledTime().roundTo(NANOSECONDS));
        totalCpuTime.getAndAdd(driverStats.getTotalCpuTime().roundTo(NANOSECONDS));

        totalBlockedTime.getAndAdd(driverStats.getTotalBlockedTime().roundTo(NANOSECONDS));

        // merge the operator stats into the operator summary
        List<OperatorStats> operators = driverStats.getOperatorStats();
        for (OperatorStats operator : operators) {
            // TODO: replace with ConcurrentMap.compute() when we migrate to java 8
            OperatorStats updated;
            OperatorStats current;
            do {
                current = operatorSummaries.get(operator.getOperatorId());
                if (current != null) {
                    updated = current.add(operator);
                }
                else {
                    updated = operator;
                }
            }
            while (!compareAndSet(operatorSummaries, operator.getOperatorId(), current, updated));
        }

        physicalInputDataSize.update(driverStats.getPhysicalInputDataSize().toBytes());
        physicalInputPositions.update(driverStats.getPhysicalInputPositions());

        internalNetworkInputDataSize.update(driverStats.getInternalNetworkInputDataSize().toBytes());
        internalNetworkInputPositions.update(driverStats.getInternalNetworkInputPositions());

        rawInputDataSize.update(driverStats.getRawInputDataSize().toBytes());
        rawInputPositions.update(driverStats.getRawInputPositions());

        processedInputDataSize.update(driverStats.getProcessedInputDataSize().toBytes());
        processedInputPositions.update(driverStats.getProcessedInputPositions());

        outputDataSize.update(driverStats.getOutputDataSize().toBytes());
        outputPositions.update(driverStats.getOutputPositions());

        physicalWrittenDataSize.getAndAdd(driverStats.getPhysicalWrittenDataSize().toBytes());
        inputBlockedTime.getAndAdd(driverStats.getInputBlockedTime().roundTo(NANOSECONDS));
        outputBlockedTime.getAndAdd(driverStats.getOutputBlockedTime().roundTo(NANOSECONDS));
    }

    public void start()
    {
        DateTime now = DateTime.now();
        executionStartTime.compareAndSet(null, now);
        // always update last execution start time
        lastExecutionStartTime.set(now);

        taskContext.start();
    }

    public void failed(Throwable cause)
    {
        taskContext.failed(cause);
    }

    public boolean isDone()
    {
        return taskContext.isDone();
    }

    public synchronized ListenableFuture<?> reserveSpill(long bytes)
    {
        return taskContext.reserveSpill(bytes);
    }

    public synchronized void freeSpill(long bytes)
    {
        checkArgument(bytes >= 0, "bytes is negative");
        taskContext.freeSpill(bytes);
    }

    public LocalMemoryContext localSystemMemoryContext()
    {
        return pipelineMemoryContext.localSystemMemoryContext();
    }

    public void moreMemoryAvailable()
    {
        drivers.forEach(DriverContext::moreMemoryAvailable);
    }

    public boolean isPerOperatorCpuTimerEnabled()
    {
        return taskContext.isPerOperatorCpuTimerEnabled();
    }

    public boolean isCpuTimerEnabled()
    {
        return taskContext.isCpuTimerEnabled();
    }

    public CounterStat getProcessedInputDataSize()
    {
        CounterStat stat = new CounterStat();
        stat.merge(processedInputDataSize);
        for (DriverContext driver : drivers) {
            stat.merge(driver.getInputDataSize());
        }
        return stat;
    }

    public CounterStat getInputPositions()
    {
        CounterStat stat = new CounterStat();
        stat.merge(processedInputPositions);
        for (DriverContext driver : drivers) {
            stat.merge(driver.getInputPositions());
        }
        return stat;
    }

    public CounterStat getOutputDataSize()
    {
        CounterStat stat = new CounterStat();
        stat.merge(outputDataSize);
        for (DriverContext driver : drivers) {
            stat.merge(driver.getOutputDataSize());
        }
        return stat;
    }

    public CounterStat getOutputPositions()
    {
        CounterStat stat = new CounterStat();
        stat.merge(outputPositions);
        for (DriverContext driver : drivers) {
            stat.merge(driver.getOutputPositions());
        }
        return stat;
    }

    public long getPhysicalWrittenDataSize()
    {
        return drivers.stream()
                .mapToLong(DriverContext::getPhysicalWrittenDataSize)
                .sum();
    }

    public PipelineStatus getPipelineStatus()
    {
        return getPipelineStatus(drivers.iterator(), totalSplits.get(), completedDrivers.get(), partitioned);
    }

    public PipelineStats getPipelineStats()
    {
        // check for end state to avoid callback ordering problems
        if (taskContext.getState().isDone()) {
            DateTime now = DateTime.now();
            executionStartTime.compareAndSet(null, now);
            lastExecutionStartTime.compareAndSet(null, now);
            lastExecutionEndTime.compareAndSet(null, now);
        }

        int completedDriverNumber = this.completedDrivers.get();
        List<DriverContext> driverContexts = ImmutableList.copyOf(this.drivers);
        int totalSplitNumber = this.totalSplits.get();
        PipelineStatus pipelineStatus = getPipelineStatus(driverContexts.iterator(), totalSplitNumber, completedDriverNumber, partitioned);

        int totalDrivers = completedDriverNumber + driverContexts.size();

        Distribution queuedTimeDuplicate = this.queuedTime.duplicate();
        Distribution elapsedTimeDuplicate = this.elapsedTime.duplicate();

        long scheduledTimes = this.totalScheduledTime.get();
        long cpuTimes = this.totalCpuTime.get();
        long blockedTimes = this.totalBlockedTime.get();

        long physicalInputDataSizeTotalCount = this.physicalInputDataSize.getTotalCount();
        long physicalInputPositionsTotalCount = this.physicalInputPositions.getTotalCount();

        long internalNetworkInputDataSizeTotalCount = this.internalNetworkInputDataSize.getTotalCount();
        long internalNetworkInputPositionsTotalCount = this.internalNetworkInputPositions.getTotalCount();

        long rawInputDataSizeTotalCount = this.rawInputDataSize.getTotalCount();
        long rawInputPositionsTotalCount = this.rawInputPositions.getTotalCount();

        long processedInputDataSizeTotalCount = this.processedInputDataSize.getTotalCount();
        long processedInputPositionsTotalCount = this.processedInputPositions.getTotalCount();

        long outputDataSizeTotalCount = this.outputDataSize.getTotalCount();
        long outputPositionsTotalCount = this.outputPositions.getTotalCount();

        long physicalWrittenSize = this.physicalWrittenDataSize.get();

        long totalInputBlockedTime = this.inputBlockedTime.get();
        long totalOutputBlockedTime = this.outputBlockedTime.get();

        List<DriverStats> driverStatsList = new ArrayList<>();

        TreeMap<Integer, OperatorStats> operatorStatsMap = new TreeMap<>(this.operatorSummaries);
        Multimap<Integer, OperatorStats> runningOperators = ArrayListMultimap.create();
        for (DriverContext driverContext : driverContexts) {
            DriverStats driverStats = driverContext.getDriverStats();
            driverStatsList.add(driverStats);

            queuedTimeDuplicate.add(driverStats.getQueuedTime().roundTo(NANOSECONDS));
            elapsedTimeDuplicate.add(driverStats.getElapsedTime().roundTo(NANOSECONDS));

            scheduledTimes += driverStats.getTotalScheduledTime().roundTo(NANOSECONDS);
            cpuTimes += driverStats.getTotalCpuTime().roundTo(NANOSECONDS);
            blockedTimes += driverStats.getTotalBlockedTime().roundTo(NANOSECONDS);

            List<OperatorStats> operators = driverContext.getOperatorStats();
            for (OperatorStats operator : operators) {
                runningOperators.put(operator.getOperatorId(), operator);
            }

            physicalInputDataSizeTotalCount += driverStats.getPhysicalInputDataSize().toBytes();
            physicalInputPositionsTotalCount += driverStats.getPhysicalInputPositions();

            internalNetworkInputDataSizeTotalCount += driverStats.getInternalNetworkInputDataSize().toBytes();
            internalNetworkInputPositionsTotalCount += driverStats.getInternalNetworkInputPositions();

            rawInputDataSizeTotalCount += driverStats.getRawInputDataSize().toBytes();
            rawInputPositionsTotalCount += driverStats.getRawInputPositions();

            processedInputDataSizeTotalCount += driverStats.getProcessedInputDataSize().toBytes();
            processedInputPositionsTotalCount += driverStats.getProcessedInputPositions();

            outputDataSizeTotalCount += driverStats.getOutputDataSize().toBytes();
            outputPositionsTotalCount += driverStats.getOutputPositions();

            physicalWrittenSize += driverStats.getPhysicalWrittenDataSize().toBytes();

            totalInputBlockedTime += driverStats.getInputBlockedTime().roundTo(NANOSECONDS);
            totalOutputBlockedTime += driverStats.getOutputBlockedTime().roundTo(NANOSECONDS);
        }

        // merge the running operator stats into the operator summary
        for (Entry<Integer, OperatorStats> entry : runningOperators.entries()) {
            OperatorStats current = operatorStatsMap.get(entry.getKey());
            if (current == null) {
                current = entry.getValue();
            }
            else {
                current = current.add(entry.getValue());
            }
            operatorStatsMap.put(entry.getKey(), current);
        }

        Set<DriverStats> runningDriverStats = driverStatsList.stream()
                .filter(driver -> driver.getEndTime() == null && driver.getStartTime() != null)
                .collect(toImmutableSet());
        ImmutableSet<BlockedReason> blockedReasons = runningDriverStats.stream()
                .flatMap(driver -> driver.getBlockedReasons().stream())
                .collect(toImmutableSet());

        boolean fullyBlocked = !runningDriverStats.isEmpty() && runningDriverStats.stream().allMatch(DriverStats::isFullyBlocked);

        return new PipelineStats(
                pipelineId,

                executionStartTime.get(),
                lastExecutionStartTime.get(),
                lastExecutionEndTime.get(),

                inputPipeline,
                outputPipeline,

                totalDrivers,
                pipelineStatus.getQueuedDrivers(),
                pipelineStatus.getQueuedPartitionedDrivers(),
                pipelineStatus.getRunningDrivers(),
                pipelineStatus.getRunningPartitionedDrivers(),
                pipelineStatus.getBlockedDrivers(),
                completedDriverNumber,

                succinctBytes(pipelineMemoryContext.getUserMemory()),
                succinctBytes(pipelineMemoryContext.getRevocableMemory()),
                succinctBytes(pipelineMemoryContext.getSystemMemory()),

                queuedTimeDuplicate.snapshot(),
                elapsedTimeDuplicate.snapshot(),

                new Duration(scheduledTimes, NANOSECONDS).convertToMostSuccinctTimeUnit(),
                new Duration(cpuTimes, NANOSECONDS).convertToMostSuccinctTimeUnit(),
                new Duration(blockedTimes, NANOSECONDS).convertToMostSuccinctTimeUnit(),
                fullyBlocked,
                blockedReasons,

                succinctBytes(physicalInputDataSizeTotalCount),
                physicalInputPositionsTotalCount,

                succinctBytes(internalNetworkInputDataSizeTotalCount),
                internalNetworkInputPositionsTotalCount,

                succinctBytes(rawInputDataSizeTotalCount),
                rawInputPositionsTotalCount,

                succinctBytes(processedInputDataSizeTotalCount),
                processedInputPositionsTotalCount,

                succinctBytes(outputDataSizeTotalCount),
                outputPositionsTotalCount,

                succinctBytes(physicalWrittenSize),

                ImmutableList.copyOf(operatorStatsMap.values()),
                driverStatsList,
                new Duration(totalInputBlockedTime, NANOSECONDS).convertToMostSuccinctTimeUnit(),
                new Duration(totalOutputBlockedTime, NANOSECONDS).convertToMostSuccinctTimeUnit());
    }

    public <C, R> R accept(QueryContextVisitor<C, R> visitor, C context)
    {
        return visitor.visitPipelineContext(this, context);
    }

    public <C, R> List<R> acceptChildren(QueryContextVisitor<C, R> visitor, C context)
    {
        return drivers.stream()
                .map(driver -> driver.accept(visitor, context))
                .collect(toList());
    }

    private static <K, V> boolean compareAndSet(ConcurrentMap<K, V> map, K key, V oldValue, V newValue)
    {
        if (oldValue == null) {
            return map.putIfAbsent(key, newValue) == null;
        }

        return map.replace(key, oldValue, newValue);
    }

    @VisibleForTesting
    public MemoryTrackingContext getPipelineMemoryContext()
    {
        return pipelineMemoryContext;
    }

    private static PipelineStatus getPipelineStatus(Iterator<DriverContext> driverContextsIterator, int totalSplits, int completedDrivers, boolean partitioned)
    {
        int runningDrivers = 0;
        int blockedDrivers = 0;
        // When a split for a partitioned pipeline is delivered to a worker,
        // conceptually, the worker would have an additional driver.
        // The queuedDrivers field in PipelineStatus is supposed to represent this.
        // However, due to implementation details of SqlTaskExecution, it may defer instantiation of drivers.
        //
        // physically queued drivers: actual number of instantiated drivers whose execution hasn't started
        // conceptually queued drivers: includes assigned splits that haven't been turned into a driver
        int physicallyQueuedDrivers = 0;
        while (driverContextsIterator.hasNext()) {
            DriverContext driverContext = driverContextsIterator.next();
            if (!driverContext.isExecutionStarted()) {
                physicallyQueuedDrivers++;
            }
            else if (driverContext.isFullyBlocked()) {
                blockedDrivers++;
            }
            else {
                runningDrivers++;
            }
        }

        int queuedDrivers;
        if (partitioned) {
            queuedDrivers = totalSplits - runningDrivers - blockedDrivers - completedDrivers;
            if (queuedDrivers < 0) {
                // It is possible to observe negative here because inputs to the above expression was not taken in a snapshot.
                queuedDrivers = 0;
            }
        }
        else {
            queuedDrivers = physicallyQueuedDrivers;
        }

        return new PipelineStatus(queuedDrivers, runningDrivers, blockedDrivers, partitioned ? queuedDrivers : 0, partitioned ? runningDrivers : 0);
    }
}
