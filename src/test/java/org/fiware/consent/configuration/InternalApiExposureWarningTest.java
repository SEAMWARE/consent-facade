/*
 * Copyright 2026 Seamless Middleware Technologies S.L and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fiware.consent.configuration;

import io.micronaut.context.ApplicationContext;
import org.fiware.consent.auth.Oid4VpConfiguration;
import org.fiware.consent.provider.ProviderRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link InternalApiExposureWarning}: it must exist exactly when nothing in this service
 * restricts the internal API, and must not exist once the port isolation is configured.
 */
class InternalApiExposureWarningTest {

    /**
     * Covers the message's branches. Nothing is asserted about the text itself - the point is that
     * every combination of active internal endpoints produces one without failing.
     */
    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void warnsForEveryCombinationOfActiveInternalEndpoints(boolean oid4vpEnabled, boolean persistentRegistry) {
        Oid4VpConfiguration configuration = new Oid4VpConfiguration();
        configuration.setEnabled(oid4vpEnabled);

        assertDoesNotThrow(() -> new InternalApiExposureWarning(configuration, persistentRegistry));
    }

    @Test
    void theWarningBeanIsAbsentOnceThePortIsolationIsConfigured() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
                InternalApiPortFilter.INTERNAL_PORT_PROPERTY, 8090,
                "facade.providers.default.tmforum-base-url", "http://localhost:1"), "test")) {
            assertFalse(context.containsBean(InternalApiExposureWarning.class),
                    "with the isolation in place there is nothing to warn about");
            assertTrue(context.containsBean(InternalApiPortFilter.class),
                    "and the filter enforcing it is wired");
        }
    }

    @Test
    void theFilterIsAbsentAndTheWarningPresentWithoutThePortProperty() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
                ProviderRegistry.PERSISTENT_PROPERTY, false,
                "facade.providers.default.tmforum-base-url", "http://localhost:1"), "test")) {
            assertFalse(context.containsBean(InternalApiPortFilter.class));
            assertTrue(context.containsBean(InternalApiExposureWarning.class));
        }
    }
}
