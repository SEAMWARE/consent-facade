package org.fiware.consent.provider;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

import java.util.List;

/**
 * The request/response body of the admin {@code /providers} API (plan §11.8): the wire form of a
 * {@link ProviderConfig}, kept separate from the consent-manager-facing API in
 * {@code api/consent-facade.yaml}.
 *
 * @param key             the provider key (ignored on {@code PUT}, where the path key wins)
 * @param tmforumBaseUrl  base url of the provider's TM Forum backend
 * @param selfDescription this provider participant's own self-description URL (optional)
 * @param clientId        this provider's OID4VP {@code client_id} (optional; facade default otherwise)
 * @param scopes          this provider's OID4VP scopes (optional; facade default otherwise)
 */
@Introspected
public record ProviderRepresentation(String key, String tmforumBaseUrl, @Nullable String selfDescription,
                                     @Nullable String clientId, @Nullable List<String> scopes) {

    static ProviderRepresentation from(ProviderConfig provider) {
        return new ProviderRepresentation(provider.key(), provider.tmforumBaseUrl(), provider.selfDescription(),
                provider.clientId(), provider.scopes());
    }

    ProviderConfig toConfig(String key) {
        return new ProviderConfig(key, tmforumBaseUrl, selfDescription, clientId, scopes);
    }
}
