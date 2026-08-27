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

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.fiware.consent.auth.Oid4VpConfiguration;
import org.fiware.consent.provider.ProviderRegistry;

/**
 * Says at startup that the internal API is reachable on the public listener.
 *
 * <p>Exists so that a deployment which serves the internal API without
 * {@link InternalApiPortFilter#INTERNAL_PORT_PROPERTY port isolation} is not silent about it. The
 * endpoints hand out a token that speaks for this participant and rewrite the backends the facade
 * routes to, and the only control left in that configuration lives outside this repository.
 */
@Singleton
@Requires(missingProperty = InternalApiPortFilter.INTERNAL_PORT_PROPERTY)
@Slf4j
public class InternalApiExposureWarning {

    /**
     * @param oid4VpConfiguration whether the token endpoint is active
     * @param persistentRegistry  whether the provider admin API is active
     */
    public InternalApiExposureWarning(
            Oid4VpConfiguration oid4VpConfiguration,
            @Value("${" + ProviderRegistry.PERSISTENT_PROPERTY + ":false}") boolean persistentRegistry) {
        if (!oid4VpConfiguration.isEnabled() && !persistentRegistry) {
            return;
        }
        log.warn("The internal API ({}{}) is served on the public listener: '{}' is not configured, so"
                        + " nothing in this service restricts it. Configure a second Netty listener and"
                        + " set that property (see application.yaml), or make sure the ingress allow-list"
                        + " and NetworkPolicy really do keep these paths unreachable.",
                oid4VpConfiguration.isEnabled() ? "POST /internal/tokens" : "",
                persistentRegistry ? (oid4VpConfiguration.isEnabled() ? ", /providers" : "/providers") : "",
                InternalApiPortFilter.INTERNAL_PORT_PROPERTY);
    }
}
