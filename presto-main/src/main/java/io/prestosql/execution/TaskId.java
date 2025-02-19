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
package io.prestosql.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.prestosql.spi.QueryId;
import org.openjdk.jol.info.ClassLayout;

import java.util.List;
import java.util.Objects;

import static com.google.common.base.Preconditions.checkArgument;
import static io.prestosql.spi.util.SizeOf.estimatedSizeOf;
import static java.lang.Integer.parseInt;

public class TaskId
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(TaskId.class).instanceSize();

    @JsonCreator
    public static TaskId valueOf(String taskId)
    {
        return new TaskId(taskId);
    }

    private final String fullId;

    public TaskId(String queryId, int stageId, int id, int attemptId)
    {
        checkArgument(id >= 0, "id is negative");
        checkArgument(attemptId >= 0, "attemptId is negative");
        this.fullId = queryId + "." + stageId + "." + id + "." + attemptId;
    }

    public TaskId(StageId stageId, int id, int attemptId)
    {
        checkArgument(id >= 0, "id is negative");
        checkArgument(attemptId >= 0, "attemptId is negative");
        this.fullId = stageId.getQueryId().getId() + "." + stageId.getId() + "." + id + "." + attemptId;
    }

    public TaskId(String fullId)
    {
        this.fullId = fullId;
    }

    public QueryId getQueryId()
    {
        return new QueryId(QueryId.parseDottedId(fullId, 4, "taskId").get(0));
    }

    public StageId getStageId()
    {
        List<String> ids = QueryId.parseDottedId(fullId, 4, "taskId");
        return StageId.valueOf(ids.subList(0, 2));
    }

    public int getId()
    {
        return parseInt(QueryId.parseDottedId(fullId, 4, "taskId").get(2));
    }

    public int getAttemptId()
    {
        return parseInt(QueryId.parseDottedId(fullId, 4, "taskId").get(3));
    }

    @Override
    @JsonValue
    public String toString()
    {
        return fullId;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(fullId);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        TaskId other = (TaskId) obj;
        return Objects.equals(this.fullId, other.fullId);
    }

    public long getRetainedSizeInBytes()
    {
        return INSTANCE_SIZE + estimatedSizeOf(fullId);
    }
}
