/*
 * Copyright (c) 2026 Oracle and/or its affiliates.
 *
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
package io.helidon.microprofile.metrics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.helidon.common.Weighted;
import io.helidon.common.types.TypeName;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.metrics.api.MeterRegistry;
import io.helidon.metrics.api.MetricsConfig;
import io.helidon.metrics.api.MetricsFactory;
import io.helidon.metrics.api.ScopingConfig;
import io.helidon.metrics.spi.MeterRegistryLifeCycleListener;
import io.helidon.metrics.spi.MetricsProgrammaticConfig;
import io.helidon.microprofile.config.core.MpConfigSources;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.ServiceLoader__ServiceDescriptor;
import io.helidon.service.registry.ServiceRegistryConfig;
import io.helidon.service.registry.ServiceRegistryManager;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class RegistryFactoryIsolationTest {

    @Test
    @Tag("micrometer")
    void tagsDirectMetersBeforeOtherLifecycleListeners() {
        var descriptor = ServiceLoader__ServiceDescriptor.create(TypeName.create(MeterRegistryLifeCycleListener.class),
                                                                 StartupMeterListener.class,
                                                                 StartupMeterListener::new,
                                                                 Weighted.DEFAULT_WEIGHT);
        ServiceRegistryManager manager = ServiceRegistryManager.create(ServiceRegistryConfig.builder()
                                                                              .addServiceDescriptor(descriptor)
                                                                              .build());
        try {
            MeterRegistry registry = manager.registry().get(MeterRegistry.class);
            var counter = registry.unwrap(io.micrometer.core.instrument.MeterRegistry.class).counter("early.direct");
            assertThat("The later listener registered the counter", counter.count(), is(3.0));
            assertThat("Scope was assigned before the other listener registered its meter",
                       counter.getId().getTag(MpScope.TAG_NAME),
                       is("application"));
            assertThat("No MP container has activated the registry", GlobalServiceRegistry.configured(), is(false));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void usesProgrammaticDefaultScopeFromOwningRegistry() {
        assertProgrammaticDefaultScope(false);
    }

    @Test
    @Tag("micrometer")
    void usesProgrammaticDefaultScopeForNativeMeters() {
        assertProgrammaticDefaultScope(true);
    }

    @Test
    void registryMetricsFactoryUsesRegistryConfig() {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        org.eclipse.microprofile.config.Config originalConfig = ConfigProvider.getConfig();
        org.eclipse.microprofile.config.Config processConfig = resolver.getBuilder()
                .withSources(MpConfigSources.create(Map.of(
                        "mp.metrics.tags", "source=process",
                        "mp.metrics.appName", "process-app")))
                .build();
        Config registryConfig = Config.just(ConfigSources.create(Map.of(
                "mp.metrics.tags", "source=registry",
                "mp.metrics.appName", "registry-app")));

        resolver.releaseConfig(originalConfig);
        ServiceRegistryManager manager = null;
        try {
            resolver.registerConfig(processConfig, classLoader);
            manager = ServiceRegistryManager.create(ServiceRegistryConfig.builder()
                                                            .putContractInstance(Config.class, registryConfig)
                                                            .build());

            MetricsConfig metricsConfig = manager.registry().get(MetricsFactory.class).metricsConfig();
            Map<String, String> tags = new HashMap<>();
            metricsConfig.tags().forEach(tag -> tags.put(tag.key(), tag.value()));

            assertThat("registry-owned global tags", tags, is(Map.of("source", "registry")));
            assertThat("registry-owned app name", metricsConfig.appName(), is(Optional.of("registry-app")));
            assertThat("isolated registry does not initialize global registry",
                       GlobalServiceRegistry.configured(),
                       is(false));
        } finally {
            if (manager != null) {
                manager.shutdown();
            }
            resolver.releaseConfig(processConfig);
            resolver.registerConfig(originalConfig, classLoader);
        }
    }

    @Test
    void suppliedMeterRegistryDoesNotInitializeGlobalServiceRegistry() {
        assertThat(GlobalServiceRegistry.configured(), is(false));

        ServiceRegistryManager manager = ServiceRegistryManager.create();
        MeterRegistry meterRegistry = null;
        RegistryFactory registryFactory = null;
        try {
            MetricsFactory metricsFactory = manager.registry().get(MetricsFactory.class);
            meterRegistry = metricsFactory.createMeterRegistry(metricsFactory.metricsConfig());
            registryFactory = RegistryFactory.create(meterRegistry);

            assertThat(GlobalServiceRegistry.configured(), is(false));
        } finally {
            if (registryFactory != null) {
                registryFactory.close();
            }
            if (meterRegistry != null) {
                meterRegistry.close();
            }
            GlobalServiceRegistry.registry(manager.registry());
            manager.shutdown();
        }
        assertThat(GlobalServiceRegistry.configured(), is(false));
    }

    private static void assertProgrammaticDefaultScope(boolean checkNativeMeters) {
        for (String defaultScope : List.of("vendor", "custom")) {
            Config config = Config.just(ConfigSources.create(Map.of("metrics.scoping.default", "application")));
            MetricsProgrammaticConfig override = new MetricsProgrammaticConfig() {
                @Override
                @SuppressWarnings("removal")
                public MetricsConfig.Builder apply(MetricsConfig.Builder builder) {
                    return builder.scoping(ScopingConfig.builder().defaultValue(defaultScope).build());
                }
            };
            ServiceRegistryManager manager = ServiceRegistryManager.create(ServiceRegistryConfig.builder()
                    .putContractInstance(Config.class, config)
                    .putContractInstance(MetricsProgrammaticConfig.class, override)
                    .build());
            try {
                MeterRegistry registry = manager.registry().get(MeterRegistry.class);
                MetricsFactory factory = manager.registry().get(MetricsFactory.class);
                var counter = registry.getOrCreate(factory.counterBuilder("overridden.default"));
                assertThat("Neutral meter uses the effective scope in " + defaultScope,
                           counter.id().tagsMap().get(MpScope.TAG_NAME),
                           is(defaultScope));
                if (checkNativeMeters) {
                    var nativeCounter = registry.unwrap(io.micrometer.core.instrument.MeterRegistry.class)
                            .counter("overridden.direct");
                    assertThat("Native meter uses the same effective scope in " + defaultScope,
                               nativeCounter.getId().getTag(MpScope.TAG_NAME),
                               is(defaultScope));
                }
                assertThat(GlobalServiceRegistry.configured(), is(false));
            } finally {
                manager.shutdown();
            }
        }
    }

    private static class StartupMeterListener implements MeterRegistryLifeCycleListener {
        @Override
        public void onCreate(MeterRegistry meterRegistry, MetricsConfig metricsConfig) {
            meterRegistry.unwrap(io.micrometer.core.instrument.MeterRegistry.class).counter("early.direct").increment(3);
        }
    }
}
