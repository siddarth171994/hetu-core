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
package io.prestosql.elasticsearch;

import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.FromStringDeserializer;
import com.google.inject.Binder;
import com.google.inject.Scopes;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.prestosql.elasticsearch.optimization.ElasticSearchPlanOptimizer;
import io.prestosql.elasticsearch.optimization.ElasticSearchPlanOptimizerProvider;
import io.prestosql.elasticsearch.optimization.ElasticSearchRowExpressionConverter;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeManager;

import javax.inject.Inject;

import static com.google.inject.multibindings.OptionalBinder.newOptionalBinder;
import static io.airlift.configuration.ConditionalModule.installModuleIf;
import static io.airlift.configuration.ConfigBinder.configBinder;
import static io.airlift.json.JsonBinder.jsonBinder;
import static io.prestosql.elasticsearch.ElasticsearchConfig.Security.PASSWORD;
import static io.prestosql.spi.type.TypeSignature.parseTypeSignature;
import static java.util.Objects.requireNonNull;
import static java.util.function.Predicate.isEqual;
import static org.weakref.jmx.guice.ExportBinder.newExporter;

public class ElasticsearchConnectorModule
        extends AbstractConfigurationAwareModule
{
    @Override
    protected void setup(Binder binder)
    {
        binder.bind(ElasticsearchConnector.class).in(Scopes.SINGLETON);
        binder.bind(ElasticsearchMetadata.class).in(Scopes.SINGLETON);
        binder.bind(ElasticsearchSplitManager.class).in(Scopes.SINGLETON);
        binder.bind(ElasticsearchPageSourceProvider.class).in(Scopes.SINGLETON);
        binder.bind(ElasticsearchClient.class).in(Scopes.SINGLETON);
        binder.bind(ElasticSearchRowExpressionConverter.class).in(Scopes.SINGLETON);
        binder.bind(ElasticSearchPlanOptimizer.class).in(Scopes.SINGLETON);
        binder.bind(ElasticSearchPlanOptimizerProvider.class).in(Scopes.SINGLETON);

        newExporter(binder).export(ElasticsearchClient.class).withGeneratedName();

        configBinder(binder).bindConfig(ElasticsearchConfig.class);

        jsonBinder(binder).addDeserializerBinding(Type.class).to(TypeDeserializer.class);

        newOptionalBinder(binder, PasswordConfig.class);

        install(installModuleIf(
                ElasticsearchConfig.class,
                config -> config.getSecurity()
                        .filter(isEqual(PASSWORD))
                        .isPresent(),
                conditionalBinder -> configBinder(conditionalBinder).bindConfig(PasswordConfig.class)));
    }

    private static final class TypeDeserializer
            extends FromStringDeserializer<Type>
    {
        private static final long serialVersionUID = 1L;

        private final TypeManager typeManager;

        @Inject
        public TypeDeserializer(TypeManager typeManager)
        {
            super(Type.class);
            this.typeManager = requireNonNull(typeManager, "typeManager is null");
        }

        @Override
        protected Type _deserialize(String value, DeserializationContext context)
        {
            return typeManager.getType(parseTypeSignature(value));
        }
    }
}
