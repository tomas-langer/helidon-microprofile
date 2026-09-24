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

import java.time.Duration;
import java.util.List;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.microprofile.testing.AddConfig;
import io.helidon.microprofile.testing.AddConfigBlock;
import io.helidon.microprofile.testing.junit5.HelidonTest;

import jakarta.inject.Inject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@HelidonTest
@AddConfig(key = "metrics.permit-all", value = "true")
@AddConfigBlock("""
        mp.metrics.distribution.timer.buckets=compat.timer=1s,2s;compat.hidden=1s
        mp.metrics.distribution.histogram.buckets=compat.histogram=10,20
        mp.metrics.distribution.percentiles=compat.*=0.5,0.9
        metrics.scoping.scopes.0.name=application
        metrics.scoping.scopes.0.filter.exclude=compat[.]hidden
        """)
class MpHistogramEndpointTest {
    private static final String TIMER = "compat.timer";
    private static final String HISTOGRAM = "compat.histogram";
    private static final String LABELS = "mp_scope=\"application\",tier=\"west\\\"\\\\\\n\"";

    @Inject
    private WebTarget webTarget;

    @BeforeEach
    void createHistograms() {
        RegistryFactory factory = RegistryFactory.getInstance();
        for (String scope : List.of(MetricRegistry.APPLICATION_SCOPE, MetricRegistry.VENDOR_SCOPE)) {
            MetricRegistry registry = factory.getRegistry(scope);
            registry.remove(TIMER);
            registry.remove(HISTOGRAM);
            Tag tag = new Tag("tier", "west\"\\\n");
            registry.timer(TIMER, tag).update(Duration.ofMillis(1500));
            registry.histogram(HISTOGRAM, tag).update(15);
        }
        factory.getRegistry(MetricRegistry.APPLICATION_SCOPE).timer("compat.hidden").update(Duration.ofMillis(500));
    }

    @Test
    void includesHistogramQuantilesWithScopeAndNameSelection() {
        String output = webTarget.path("metrics")
                .queryParam("scope", MetricRegistry.APPLICATION_SCOPE)
                .queryParam("name", TIMER, HISTOGRAM)
                .request(MediaType.TEXT_PLAIN)
                .get(String.class);

        assertThat(output, containsString("compat_timer_seconds{" + LABELS + ",quantile=\"0.5\"} 1.5"));
        assertThat(output, containsString("compat_histogram{" + LABELS + ",quantile=\"0.9\"} 15.0"));
        assertThat(output, containsString("compat_timer_seconds_bucket{" + LABELS + ",le=\"2.0\"} 1\n"));
        assertThat(output, containsString("compat_histogram_bucket{" + LABELS + ",le=\"20.0\"} 1\n"));
        assertThat(output, not(containsString("mp_scope=\"vendor\"")));
        assertThat(output, not(containsString("compat_hidden")));
        for (String family : List.of("compat_timer_seconds", "compat_histogram")) {
            assertThat("One type declaration for " + family,
                       output.lines().filter(line -> line.equals("# TYPE " + family + " histogram")).count(),
                       is(1L));
            for (String suffix : List.of("_sum", "_count")) {
                assertThat("One " + suffix + " sample for " + family,
                           output.lines().filter(line -> line.startsWith(family + suffix + "{")).count(),
                           is(1L));
            }
        }

        String namedTimer = webTarget.path("metrics/application/" + TIMER)
                .request(MediaType.TEXT_PLAIN)
                .get(String.class);
        assertThat(namedTimer, containsString("quantile=\"0.5\""));
        assertThat(namedTimer, not(containsString("compat_histogram")));
        assertThat(namedTimer, not(containsString("mp_scope=\"vendor\"")));

        String aggregate = webTarget.path("metrics").request(MediaType.TEXT_PLAIN).get(String.class);
        assertThat(aggregate, containsString("compat_timer_seconds{" + LABELS + ",quantile=\"0.5\"} 1.5"));
        assertThat(aggregate, not(containsString("compat_hidden")));
    }

    @Test
    void keepsOpenMetricsHistogramsFreeOfQuantileSamples() {
        String output = webTarget.path("metrics")
                .queryParam("scope", MetricRegistry.APPLICATION_SCOPE)
                .queryParam("name", TIMER, HISTOGRAM)
                .request(MediaTypes.APPLICATION_OPENMETRICS_TEXT.text())
                .get(String.class);

        assertThat(output, containsString("compat_timer_seconds_bucket{" + LABELS));
        assertThat(output, containsString("compat_histogram_bucket{" + LABELS));
        assertThat(output, not(containsString("quantile=")));
        assertThat(output, not(containsString("mp_scope=\"vendor\"")));
        assertThat(output, endsWith("# EOF\n"));
    }
}
