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
package io.hetu.core.plugin.iceberg;

import com.google.common.collect.ImmutableList;
import com.google.inject.Module;
import io.prestosql.plugin.hive.metastore.HiveMetastore;
import io.prestosql.spi.connector.ConnectorFactory;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Verify.verify;
import static java.util.Objects.requireNonNull;

public class TestingIcebergPlugin
        extends IcebergPlugin
{
    private final Optional<HiveMetastore> metastore;
    private final Optional<FileIoProvider> fileIoProvider;
    private final Module module;

    public TestingIcebergPlugin(Optional<HiveMetastore> metastore, Optional<FileIoProvider> fileIoProvider, Module module)
    {
        this.metastore = requireNonNull(metastore, "metastore is null");
        this.fileIoProvider = requireNonNull(fileIoProvider, "fileIoProvider is null");
        this.module = requireNonNull(module, "module is null");
    }

    @Override
    public Iterable<ConnectorFactory> getConnectorFactories()
    {
        List<ConnectorFactory> connectorFactories = ImmutableList.copyOf(super.getConnectorFactories());
        verify(connectorFactories.size() == 1, "Unexpected connector factories: %s", connectorFactories);

        return ImmutableList.of(new TestingIcebergConnectorFactory(metastore, fileIoProvider, module));
    }
}
