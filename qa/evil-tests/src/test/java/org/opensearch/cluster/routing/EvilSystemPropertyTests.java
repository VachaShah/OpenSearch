/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.cluster.routing;

import org.opensearch.common.SuppressForbidden;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchTestCase;

import static org.hamcrest.Matchers.equalTo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class EvilSystemPropertyTests extends OpenSearchTestCase {

    // This set will contain the warnings already asserted since we are eliminating logging duplicate warnings.
    // This ensures that no matter in what order the tests run, the warning is asserted once.
    private static Set<String> assertedWarnings = new HashSet<>();

    @SuppressForbidden(reason = "manipulates system properties for testing")
    public void testDisableSearchAllocationAwareness() {
        Settings indexSettings = Settings.builder()
            .put("cluster.routing.allocation.awareness.attributes", "test")
            .build();
        OperationRouting routing = new OperationRouting(indexSettings,
            new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS));
        assertWarningsOnce(Arrays.asList(OperationRouting.IGNORE_AWARENESS_ATTRIBUTES_DEPRECATION_MESSAGE), assertedWarnings);
        assertThat(routing.getAwarenessAttributes().size(), equalTo(1));
        assertThat(routing.getAwarenessAttributes().get(0), equalTo("test"));
        System.setProperty("opensearch.search.ignore_awareness_attributes", "true");
        try {
            routing = new OperationRouting(indexSettings,
                new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS));
            assertTrue(routing.getAwarenessAttributes().isEmpty());
        } finally {
            System.clearProperty("opensearch.search.ignore_awareness_attributes");
        }

    }
}
