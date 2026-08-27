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
