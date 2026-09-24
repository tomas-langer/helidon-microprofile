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
import io.helidon.microprofile.testing.AddConfig;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import io.helidon.service.registry.Services;

import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;

@HelidonTest
@AddConfig(key = "metrics.permit-all", value = "true")
class MpMetricsUnrestrictedEndpointTest {
    private static final String APPLICATION_METER = "unrestricted.application";
    private static final String SHARED_STRUCTURED_METER = "unrestricted.shared.structured";

    @Inject
    private WebTarget webTarget;

    @BeforeEach
    void createMeter() {
        RegistryFactory registryFactory = RegistryFactory.getInstance();
        registryFactory.getRegistry(MetricRegistry.APPLICATION_SCOPE).counter(APPLICATION_METER);
        registryFactory.getRegistry(MetricRegistry.APPLICATION_SCOPE).timer(SHARED_STRUCTURED_METER);
        registryFactory.getRegistry(MetricRegistry.VENDOR_SCOPE).histogram(SHARED_STRUCTURED_METER);
    }

    @Test
    void usesOneUnrestrictedFormatterInvocationForDefaultRequest() {
        TestMeterRegistryFormatterProvider.startRecording();
        try {
            String aggregate = webTarget.path("metrics")
                    .request()
                    .accept(MediaType.TEXT_PLAIN)
                    .get(String.class);
            assertThat(aggregate, containsString(APPLICATION_METER.replace('.', '_') + "_total"));
        } finally {
            TestMeterRegistryFormatterProvider.stopRecording();
        }
        assertThat(TestMeterRegistryFormatterProvider.invocationCount(), is(1));
        assertThat(TestMeterRegistryFormatterProvider.allSelectionsEmpty(), is(true));
    }

    @Test
    void groupsUnrestrictedJsonByMetricType() {
        JsonObject aggregate = webTarget.path("metrics")
                .request(MediaType.APPLICATION_JSON_TYPE)
                .get(JsonObject.class);

        JsonObject shared = aggregate.getJsonObject(SHARED_STRUCTURED_METER);
        assertThat(shared, is(notNullValue()));
        assertThat(shared.containsKey(jsonName("elapsedTime", MetricRegistry.APPLICATION_SCOPE)), is(true));
        assertThat(shared.containsKey(jsonName("total", MetricRegistry.VENDOR_SCOPE)), is(true));
    }

    @Test
    @Tag("micrometer")
    void includesDirectMicrometerRegistrationInSelectedOutput() {
        String name = "unrestricted.direct.micrometer";
        var nativeRegistry = Services.get(MeterRegistry.class).unwrap(io.micrometer.core.instrument.MeterRegistry.class);
        var counter = nativeRegistry.counter(name);
        counter.increment(7);

        String aggregate = webTarget.path("metrics").request(MediaType.TEXT_PLAIN_TYPE).get(String.class);
        assertThat(aggregate, containsString(name.replace('.', '_') + "_total"));
        for (String mediaType : List.of(MediaType.TEXT_PLAIN, MediaTypes.APPLICATION_OPENMETRICS_TEXT.text())) {
            String selected = webTarget.path("metrics")
                    .queryParam("name", name)
                    .request(mediaType)
                    .get(String.class);
            assertThat(selected, containsString("mp_scope=\"application\""));
        }

        RegistryFactory.getInstance().getRegistry(MetricRegistry.VENDOR_SCOPE).counter(name).inc(11);
        for (String mediaType : List.of(MediaType.TEXT_PLAIN, MediaTypes.APPLICATION_OPENMETRICS_TEXT.text())) {
            String selected = webTarget.path("metrics/application/" + name).request(mediaType).get(String.class);
            assertThat(selected, containsString("mp_scope=\"application\""));
            assertThat(selected, not(containsString("mp_scope=\"vendor\"")));
        }

        JsonObject json = webTarget.path("metrics").request(MediaType.APPLICATION_JSON_TYPE).get(JsonObject.class);
        assertThat(json.getJsonNumber(jsonName(name, MetricRegistry.APPLICATION_SCOPE)).doubleValue(), is(7.0));
        JsonObject selectedJson = webTarget.path("metrics")
                .queryParam("scope", MetricRegistry.APPLICATION_SCOPE)
                .queryParam("name", name)
                .request(MediaType.APPLICATION_JSON_TYPE)
                .get(JsonObject.class);
        assertThat(selectedJson.getJsonNumber(jsonName(name, MetricRegistry.APPLICATION_SCOPE)).doubleValue(), is(7.0));
        assertThat(selectedJson.containsKey(jsonName(name, MetricRegistry.VENDOR_SCOPE)), is(false));
        assertThat(nativeRegistry.counter(name), sameInstance(counter));
        assertThat(RegistryFactory.getInstance().getRegistry(MetricRegistry.APPLICATION_SCOPE).counter(name).getCount(), is(7L));
    }

    private static String jsonName(String meterName, String scope) {
        return meterName + ";" + MpScope.TAG_NAME + "=" + scope;
    }
}
