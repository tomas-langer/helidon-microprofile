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

import io.helidon.common.media.type.MediaTypes;
import io.helidon.metrics.api.MeterRegistry;
import io.helidon.metrics.api.MetricsFactory;
import io.helidon.microprofile.testing.AddConfig;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import io.helidon.service.registry.Services;

import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@HelidonTest
@AddConfig(key = "metrics.permit-all", value = "true")
@AddConfig(key = "metrics.scoping.default", value = "custom_default")
class MpMetricsDefaultScopeEndpointTest {
    @Inject
    private WebTarget webTarget;

    @Test
    void usesConfiguredDefaultUnlessScopeOrOriginIsKnown() {
        MeterRegistry registry = Services.get(MeterRegistry.class);
        MetricsFactory factory = registry.metricsFactory();
        registry.getOrCreate(factory.counterBuilder("default.neutral")).increment(3);
        registry.getOrCreate(factory.counterBuilder("default.unknown")
                                     .origin("com.acme.ExtensionMetersProvider")).increment(5);
        registry.getOrCreate(factory.counterBuilder("default.base")
                                     .origin("io.helidon.metrics.systemmeters.SystemMetersProvider")).increment(11);
        registry.getOrCreate(factory.counterBuilder("default.vendor")
                                     .origin("io.helidon.faulttolerance.FaultTolerance")).increment(13);
        RegistryFactory.getInstance().getRegistry(MetricRegistry.APPLICATION_SCOPE).counter("default.explicit").inc(17);

        JsonObject aggregate = webTarget.path("metrics").request(MediaType.APPLICATION_JSON_TYPE).get(JsonObject.class);
        assertCount(aggregate, "default.neutral;mp_scope=custom_default", 3);
        assertCount(aggregate, "default.unknown;mp_scope=custom_default", 5);
        assertCount(aggregate, "default.base;mp_scope=base", 11);
        assertCount(aggregate, "default.vendor;mp_scope=vendor", 13);
        assertCount(aggregate, "default.explicit;mp_scope=application", 17);

        for (String mediaType : List.of(MediaType.TEXT_PLAIN, MediaTypes.APPLICATION_OPENMETRICS_TEXT.text())) {
            String selected = webTarget.path("metrics")
                    .queryParam("scope", "custom_default")
                    .request(mediaType)
                    .get(String.class);
            assertThat(selected, containsString("default_neutral_total"));
            assertThat(selected, containsString("default_unknown_total"));
            assertThat(selected, not(containsString("default_base_total")));
            assertThat(selected, not(containsString("default_vendor_total")));
            assertThat(selected, not(containsString("default_explicit_total")));
        }
    }

    @Test
    @Tag("micrometer")
    void usesConfiguredDefaultForNativeMeters() {
        Services.get(MeterRegistry.class)
                .unwrap(io.micrometer.core.instrument.MeterRegistry.class)
                .counter("default.direct")
                .increment(7);
        JsonObject aggregate = webTarget.path("metrics").request(MediaType.APPLICATION_JSON_TYPE).get(JsonObject.class);
        assertCount(aggregate, "default.direct;mp_scope=custom_default", 7);
        for (String mediaType : List.of(MediaType.TEXT_PLAIN, MediaTypes.APPLICATION_OPENMETRICS_TEXT.text())) {
            String selected = webTarget.path("metrics")
                    .queryParam("scope", "custom_default")
                    .request(mediaType)
                    .get(String.class);
            assertThat(selected, containsString("default_direct_total{mp_scope=\"custom_default\"} 7.0"));
        }
    }

    private static void assertCount(JsonObject json, String key, long expected) {
        assertThat("Reported metric " + key + ": " + json.keySet(), json.getJsonNumber(key), notNullValue());
        assertThat("Value of " + key, json.getJsonNumber(key).longValue(), is(expected));
    }
}
