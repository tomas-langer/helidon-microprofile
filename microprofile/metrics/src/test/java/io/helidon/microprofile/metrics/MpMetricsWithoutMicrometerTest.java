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

import io.helidon.metrics.api.MeterRegistry;
import io.helidon.metrics.api.MetricsConfig;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MpMetricsWithoutMicrometerTest {
    @Test
    void initializesWithoutOptionalMicrometerClasses() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("io.micrometer.core.instrument.MeterRegistry"));
        ServiceRegistryManager manager = ServiceRegistryManager.create();
        try {
            MeterRegistry meterRegistry = manager.registry().get(MeterRegistry.class);
            RegistryFactoryManager registryFactoryManager = manager.registry().get(RegistryFactoryManager.class);
            MetricsConfig metricsConfig = meterRegistry.metricsFactory().metricsConfig();
            boolean configuredBeforeCallback = GlobalServiceRegistry.configured();
            registryFactoryManager.onCreate(meterRegistry, metricsConfig);
            var counter = meterRegistry.getOrCreate(meterRegistry.metricsFactory().counterBuilder("without.micrometer"));
            counter.increment(7);
            assertThat("The default provider records metrics without Micrometer", counter.count(), is(7L));
            assertThat("The optional integration leaves global registry state unchanged",
                       GlobalServiceRegistry.configured(),
                       is(configuredBeforeCallback));
        } finally {
            manager.shutdown();
        }
    }
}
