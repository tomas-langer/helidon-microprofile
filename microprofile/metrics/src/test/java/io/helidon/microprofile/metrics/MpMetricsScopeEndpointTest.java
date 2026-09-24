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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.metrics.api.MeterRegistry;
import io.helidon.microprofile.testing.AddConfig;
import io.helidon.microprofile.testing.AddConfigBlock;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import io.helidon.service.registry.Services;

import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@HelidonTest
@AddConfig(key = "metrics.permit-all", value = "true")
@AddConfigBlock("""
        metrics.scoping.scopes.0.name=base
        metrics.scoping.scopes.0.filter.exclude=thread[.]count
        metrics.scoping.scopes.1.name=application
        metrics.scoping.scopes.1.filter.exclude=direct[.]hidden
        """)
class MpMetricsScopeEndpointTest {
    private static final String APPLICATION_METER = "scope.application";
    private static final String BASE_METER = "scope.base";
    private static final String VENDOR_METER = "scope.vendor";
    private static final String SHARED_PROMETHEUS_METER = "scope.shared.prometheus";
    private static final String SHARED_STRUCTURED_METER = "scope.shared.structured";
    private static final String DISABLED_BASE_METER = "thread.count";
    private static final String UNKNOWN_SCOPE = "unknown-scope";

    @Inject
    private WebTarget webTarget;

    @BeforeEach
    void createMeters() {
        RegistryFactory registryFactory = RegistryFactory.getInstance();
        registryFactory.getRegistry(MetricRegistry.APPLICATION_SCOPE).counter(APPLICATION_METER);
        registryFactory.getRegistry(MetricRegistry.APPLICATION_SCOPE).gauge(SHARED_PROMETHEUS_METER, () -> 1);
        registryFactory.getRegistry(MetricRegistry.APPLICATION_SCOPE).timer(SHARED_STRUCTURED_METER);
        registryFactory.getRegistry(MetricRegistry.BASE_SCOPE).counter(BASE_METER);
        registryFactory.getRegistry(MetricRegistry.VENDOR_SCOPE).counter(VENDOR_METER);
        registryFactory.getRegistry(MetricRegistry.VENDOR_SCOPE).gauge(SHARED_PROMETHEUS_METER, () -> 2);
        registryFactory.getRegistry(MetricRegistry.VENDOR_SCOPE).histogram(SHARED_STRUCTURED_METER);
    }

    @Test
    void isolatesMetricsUsingLegacyScopePaths() {
        assertOnlyExpectedMeter(textAt("metrics/application"), APPLICATION_METER, BASE_METER, VENDOR_METER);
        assertOnlyExpectedMeter(textAt("metrics/base"), BASE_METER, APPLICATION_METER, VENDOR_METER);
        assertOnlyExpectedMeter(textAt("metrics/vendor"), VENDOR_METER, APPLICATION_METER, BASE_METER);

        String namedBase = textAt("metrics/base/" + BASE_METER);
        assertThat(namedBase, containsString(prometheusName(BASE_METER)));
        assertThat(namedBase, not(containsString(prometheusName(APPLICATION_METER))));
    }

    @Test
    void filtersMetricsUsingScopeAndNameQueries() {
        String aggregate = textAt("metrics");
        assertThat(aggregate, containsString(prometheusName(APPLICATION_METER)));
        assertThat(aggregate, containsString(prometheusName(BASE_METER)));
        assertThat(aggregate, containsString(prometheusName(VENDOR_METER)));

        String base = webTarget.path("metrics")
                .queryParam("scope", MetricRegistry.BASE_SCOPE)
                .request()
                .accept(MediaType.TEXT_PLAIN)
                .get(String.class);
        assertOnlyExpectedMeter(base, BASE_METER, APPLICATION_METER, VENDOR_METER);

        String applicationAndVendor = webTarget.path("metrics")
                .queryParam("scope", MetricRegistry.APPLICATION_SCOPE, MetricRegistry.VENDOR_SCOPE)
                .request()
                .accept(MediaType.TEXT_PLAIN)
                .get(String.class);
        assertThat(applicationAndVendor, containsString(prometheusName(APPLICATION_METER)));
        assertThat(applicationAndVendor, containsString(prometheusName(VENDOR_METER)));
        assertThat(applicationAndVendor, not(containsString(prometheusName(BASE_METER))));

        String namedBase = webTarget.path("metrics")
                .queryParam("scope", MetricRegistry.BASE_SCOPE)
                .queryParam("name", BASE_METER)
                .request()
                .accept(MediaType.TEXT_PLAIN)
                .get(String.class);
        assertOnlyExpectedMeter(namedBase, BASE_METER, APPLICATION_METER, VENDOR_METER);
    }

    @Test
    void formatsFlatJsonUsingMpScopeTags() {
        JsonObject aggregate = webTarget.path("metrics")
                .request(MediaType.APPLICATION_JSON_TYPE)
                .get(JsonObject.class);
        assertThat(aggregate.containsKey(jsonName(APPLICATION_METER, MetricRegistry.APPLICATION_SCOPE)), is(true));
        assertThat(aggregate.containsKey(jsonName(BASE_METER, MetricRegistry.BASE_SCOPE)), is(true));
        assertThat(aggregate.containsKey(jsonName(VENDOR_METER, MetricRegistry.VENDOR_SCOPE)), is(true));

        JsonObject base = webTarget.path("metrics/base")
                .request(MediaType.APPLICATION_JSON_TYPE)
                .get(JsonObject.class);
        assertThat(base.containsKey(jsonName(BASE_METER, MetricRegistry.BASE_SCOPE)), is(true));
        assertThat(base.containsKey(jsonName(APPLICATION_METER, MetricRegistry.APPLICATION_SCOPE)), is(false));
        assertThat(base.containsKey(jsonName(VENDOR_METER, MetricRegistry.VENDOR_SCOPE)), is(false));
    }

    @Test
    void formatsSameNameWithDifferentTypesAcrossScopesAsJson() {
        JsonObject aggregate = webTarget.path("metrics")
                .queryParam("name", SHARED_STRUCTURED_METER)
                .request(MediaType.APPLICATION_JSON_TYPE)
                .get(JsonObject.class);

        JsonObject shared = aggregate.getJsonObject(SHARED_STRUCTURED_METER);
        assertThat(shared, is(notNullValue()));
        assertThat(shared.containsKey(jsonName("elapsedTime", MetricRegistry.APPLICATION_SCOPE)), is(true));
        assertThat(shared.containsKey(jsonName("total", MetricRegistry.VENDOR_SCOPE)), is(true));
    }

    @Test
    void formatsEachPrometheusFamilyOnceAcrossScopes() {
        for (String mediaType : List.of(MediaType.TEXT_PLAIN, MediaTypes.APPLICATION_OPENMETRICS_TEXT.text())) {
            String namedOutput = webTarget.path("metrics")
                    .queryParam("name", SHARED_PROMETHEUS_METER)
                    .request()
                    .accept(mediaType)
                    .get(String.class);
            String familyName = SHARED_PROMETHEUS_METER.replace('.', '_');
            assertThat(namedOutput, containsString(familyName + "{mp_scope=\"application\"} 1.0"));
            assertThat(namedOutput, containsString(familyName + "{mp_scope=\"vendor\"} 2.0"));
            assertUniquePrometheusMetadata(namedOutput);

            String aggregateOutput = webTarget.path("metrics")
                    .request()
                    .accept(mediaType)
                    .get(String.class);
            assertUniquePrometheusMetadata(aggregateOutput);
        }
    }

    @Test
    void mergesOpenMetricsOutputWithOneTerminalMarker() {
        String output = webTarget.path("metrics")
                .request()
                .accept(MediaTypes.APPLICATION_OPENMETRICS_TEXT.text())
                .get(String.class);

        assertThat(output, containsString(prometheusName(APPLICATION_METER)));
        assertThat(output, containsString(prometheusName(BASE_METER)));
        assertThat(output, containsString(prometheusName(VENDOR_METER)));
        assertThat(output, endsWith("# EOF\n"));
        assertThat(output.indexOf("# EOF"), is(output.lastIndexOf("# EOF")));
    }

    @Test
    void unknownScopeDoesNotCreateRegistry() {
        RegistryFactory registryFactory = RegistryFactory.getInstance();
        Set<String> scopesBeforeRequest = registryFactory.scopes();

        try (Response response = webTarget.path("metrics")
                .queryParam("scope", UNKNOWN_SCOPE)
                .request()
                .accept(MediaType.TEXT_PLAIN)
                .get()) {
            assertThat("Unknown scope status", response.getStatus(), is(404));
        }

        assertThat(registryFactory.scopes(), is(scopesBeforeRequest));
    }

    @Test
    void hidesScopeDisabledCoreMeterAndReturnsNotFoundByName() {
        boolean coreMeterExists = Services.get(MeterRegistry.class)
                .meters()
                .stream()
                .anyMatch(meter -> meter.id().name().equals(DISABLED_BASE_METER));
        assertThat("Core-origin thread count meter exists", coreMeterExists, is(true));

        String base = textAt("metrics/base");
        assertThat(base, not(containsString("thread_count{")));

        try (Response response = webTarget.path("metrics/base/" + DISABLED_BASE_METER)
                .request()
                .accept(MediaType.TEXT_PLAIN)
                .get()) {
            assertThat("Disabled metric path status", response.getStatus(), is(404));
        }

        try (Response response = webTarget.path("metrics")
                .queryParam("scope", MetricRegistry.BASE_SCOPE)
                .queryParam("name", DISABLED_BASE_METER)
                .request()
                .accept(MediaType.TEXT_PLAIN)
                .get()) {
            assertThat("Disabled metric query status", response.getStatus(), is(404));
        }
    }

    @Test
    @Tag("micrometer")
    void appliesScopeExclusionToDirectMicrometerMeters() {
        String name = "direct.hidden";
        Services.get(MeterRegistry.class)
                .unwrap(io.micrometer.core.instrument.MeterRegistry.class)
                .counter(name)
                .increment(7);
        RegistryFactory.getInstance().getRegistry(MetricRegistry.VENDOR_SCOPE).counter(name).inc(11);

        for (String mediaType : List.of(MediaType.TEXT_PLAIN,
                                       MediaTypes.APPLICATION_OPENMETRICS_TEXT.text(),
                                       MediaType.APPLICATION_JSON)) {
            try (Response response = webTarget.path("metrics")
                    .queryParam("scope", MetricRegistry.APPLICATION_SCOPE)
                    .queryParam("name", name)
                    .request(mediaType)
                    .get()) {
                assertThat("Excluded direct application meter: " + mediaType, response.getStatus(), is(404));
            }
        }

        for (String mediaType : List.of(MediaType.TEXT_PLAIN, MediaTypes.APPLICATION_OPENMETRICS_TEXT.text())) {
            String aggregate = webTarget.path("metrics").request(mediaType).get(String.class);
            List<String> samples = aggregate.lines().filter(line -> line.startsWith("direct_hidden_total{")).toList();
            assertThat("Only the vendor sample is exposed", samples.size(), is(1));
            assertThat(samples.getFirst(), containsString("mp_scope=\"vendor\""));
        }
        JsonObject aggregate = webTarget.path("metrics").request(MediaType.APPLICATION_JSON_TYPE).get(JsonObject.class);
        assertThat(aggregate.containsKey(jsonName(name, MetricRegistry.APPLICATION_SCOPE)), is(false));
        assertThat(aggregate.getJsonNumber(jsonName(name, MetricRegistry.VENDOR_SCOPE)).doubleValue(), is(11.0));
    }

    private static void assertOnlyExpectedMeter(String output,
                                                String expected,
                                                String firstUnexpected,
                                                String secondUnexpected) {
        assertThat(output, containsString(prometheusName(expected)));
        assertThat(output, not(containsString(prometheusName(firstUnexpected))));
        assertThat(output, not(containsString(prometheusName(secondUnexpected))));
    }

    private static String prometheusName(String meterName) {
        return meterName.replace('.', '_') + "_total";
    }

    private static void assertUniquePrometheusMetadata(String output) {
        Set<String> descriptors = new HashSet<>();
        for (String line : output.lines().toList()) {
            if (line.startsWith("# HELP ") || line.startsWith("# TYPE ") || line.startsWith("# UNIT ")) {
                String[] fields = line.split(" ", 4);
                String descriptor = fields[1] + " " + fields[2];
                assertThat("Unique Prometheus metadata " + descriptor, descriptors.add(descriptor), is(true));
            }
        }
    }

    private static String jsonName(String meterName, String scope) {
        return meterName + ";" + MpScope.TAG_NAME + "=" + scope;
    }

    private String textAt(String path) {
        return webTarget.path(path)
                .request()
                .accept(MediaType.TEXT_PLAIN)
                .get(String.class);
    }
}
