package org.fiware.consent.provider;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

/**
 * The request/response body of the admin {@code /providers} API (plan §11.8): the wire form of a
 * {@link ProviderConfig}, kept separate from the consent-manager-facing API in
 * {@code api/consent-facade.yaml}.
 *
 * @param key             the provider key (ignored on {@code PUT}, where the path key wins)
 * @param tmforumBaseUrl  base url of the provider's TM Forum backend
 * @param selfDescription this provider participant's own self-description URL (optional)
 */
@Introspected
public record ProviderRepresentation(String key, String tmforumBaseUrl, @Nullable String selfDescription) {

    static ProviderRepresentation from(ProviderConfig provider) {
        return new ProviderRepresentation(provider.key(), provider.tmforumBaseUrl(), provider.selfDescription());
    }

    ProviderConfig toConfig(String key) {
        return new ProviderConfig(key, tmforumBaseUrl, selfDescription);
    }
}
