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

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.metrics.api.MeterRegistry;
import io.helidon.metrics.api.MetricsFactory;
import io.helidon.service.registry.ServiceRegistryConfig;
import io.helidon.service.registry.ServiceRegistryManager;

import org.eclipse.microprofile.metrics.MetricRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MpDefaultScopeValidationTest {
    private static final String DEFAULT_SCOPE_CONFIG_KEY = "metrics.scoping.default";
    private static final List<String> INVALID_SCOPES =
            List.of("", "custom-default", "1custom", "custom default", "métrics");

    @Test
    void rejectsInvalidDefaultScopeForNeutralMeters() {
        for (String defaultScope : INVALID_SCOPES) {
            withMetricsFactory(Map.of(DEFAULT_SCOPE_CONFIG_KEY, defaultScope), factory -> {
                var builder = factory.counterBuilder("invalid.default");
                var customizer = new MpMeterBuilderCustomizer(() -> factory);

                var exception = assertThrows(IllegalArgumentException.class,
                                             () -> customizer.customize(builder),
                                             "Invalid default scope '" + defaultScope + "'");
                assertThat(exception.getMessage(), containsString(DEFAULT_SCOPE_CONFIG_KEY));
                assertThat(exception.getMessage(), containsString("'" + defaultScope + "'"));
            });
        }
    }

    @Test
    @Tag("micrometer")
    void rejectsInvalidDefaultScopeBeforeConfiguringNativeMeters() {
        for (String defaultScope : INVALID_SCOPES) {
            withMetricsFactory(Map.of(DEFAULT_SCOPE_CONFIG_KEY, defaultScope), factory -> {
                MeterRegistry registry = factory.createMeterRegistry(factory.metricsConfig());
                try {
                    var exception = assertThrows(IllegalArgumentException.class,
                                                 () -> MpMicrometerSupport.configure(registry, factory.metricsConfig()),
                                                 "Invalid default scope '" + defaultScope + "'");
                    assertThat(exception.getMessage(), containsString(DEFAULT_SCOPE_CONFIG_KEY));
                    assertThat(exception.getMessage(), containsString("'" + defaultScope + "'"));
                } finally {
                    registry.close();
                }
            });
        }
    }

    @Test
    void acceptsValidDefaultScopesForNeutralMeters() {
        for (String defaultScope : List.of("application", "vendor", "base", "custom_default", "_Custom123")) {
            withMetricsFactory(Map.of(DEFAULT_SCOPE_CONFIG_KEY, defaultScope),
                               factory -> assertDefaultScope(factory, defaultScope, false));
        }
    }

    @Test
    @Tag("micrometer")
    void acceptsValidDefaultScopesForNeutralAndNativeMeters() {
        for (String defaultScope : List.of("application", "vendor", "base", "custom_default", "_Custom123")) {
            withMetricsFactory(Map.of(DEFAULT_SCOPE_CONFIG_KEY, defaultScope),
                               factory -> assertDefaultScope(factory, defaultScope, true));
        }
    }

    @Test
    void usesApplicationScopeWhenDefaultIsAbsent() {
        withMetricsFactory(Map.of(), factory -> assertDefaultScope(factory, MetricRegistry.APPLICATION_SCOPE, false));
    }

    @Test
    @Tag("micrometer")
    void usesApplicationScopeForNativeMetersWhenDefaultIsAbsent() {
        withMetricsFactory(Map.of(), factory -> assertDefaultScope(factory, MetricRegistry.APPLICATION_SCOPE, true));
    }

    @Test
    void knownOriginAndExplicitRegistrationScopesTakePrecedenceOverInvalidDefault() {
        withMetricsFactory(Map.of(DEFAULT_SCOPE_CONFIG_KEY, "invalid-default"), factory -> {
            MeterRegistry registry = factory.createMeterRegistry(factory.metricsConfig());
            try {
                var base = registry.getOrCreate(
                        factory.counterBuilder("known.base")
                                .origin("io.helidon.metrics.systemmeters.SystemMetersProvider"));
                var vendor = registry.getOrCreate(factory.counterBuilder("known.vendor")
                                                          .origin("io.helidon.faulttolerance.FaultTolerance"));
                var explicit = MpScope.getOrCreate(
                        registry,
                        factory,
                        "custom_scope",
                        factory.counterBuilder("explicit.scope")
                                .origin("io.helidon.metrics.systemmeters.SystemMetersProvider"));

                assertThat("Known base origin",
                           base.id().tagsMap().get(MpScope.TAG_NAME),
                           is(MetricRegistry.BASE_SCOPE));
                assertThat("Known vendor origin",
                           vendor.id().tagsMap().get(MpScope.TAG_NAME),
                           is(MetricRegistry.VENDOR_SCOPE));
                assertThat("Explicit registration overrides origin and default",
                           explicit.id().tagsMap().get(MpScope.TAG_NAME),
                           is("custom_scope"));
            } finally {
                registry.close();
            }
        });
    }

    private static void withMetricsFactory(Map<String, String> values, Consumer<MetricsFactory> action) {
        Config config = Config.just(ConfigSources.create(values));
        ServiceRegistryManager manager = ServiceRegistryManager.create(ServiceRegistryConfig.builder()
                                                                              .putContractInstance(Config.class, config)
                                                                              .build());
        try {
            action.accept(manager.registry().get(MetricsFactory.class));
        } finally {
            manager.shutdown();
        }
    }

    private static void assertDefaultScope(MetricsFactory factory, String expectedScope, boolean checkNativeMeters) {
        var builder = factory.counterBuilder("valid.default");
        new MpMeterBuilderCustomizer(() -> factory).customize(builder);
        assertThat("Neutral meter default scope", builder.tags().get(MpScope.TAG_NAME), is(expectedScope));

        if (checkNativeMeters) {
            MeterRegistry registry = factory.createMeterRegistry(factory.metricsConfig());
            try {
                MpMicrometerSupport.configure(registry, factory.metricsConfig());
                var counter = registry.unwrap(io.micrometer.core.instrument.MeterRegistry.class).counter("native.default");
                assertThat("Native meter default scope", counter.getId().getTag(MpScope.TAG_NAME), is(expectedScope));
            } finally {
                registry.close();
            }
        }
    }
}
