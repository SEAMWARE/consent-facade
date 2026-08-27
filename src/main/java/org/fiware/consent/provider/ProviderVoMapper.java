package org.fiware.consent.provider;

import org.fiware.consent.internal.model.ProviderVO;

/**
 * Maps between the internal API's wire form ({@link ProviderVO}, generated from
 * {@code api/consent-facade-internal.yaml}) and the {@link ProviderConfig} the registry stores.
 */
final class ProviderVoMapper {

    private ProviderVoMapper() {
        // static helper
    }

    /**
     * Converts a stored provider to its wire form.
     *
     * @param provider the stored provider
     * @return the wire form
     */
    static ProviderVO toVo(ProviderConfig provider) {
        return new ProviderVO()
                .key(provider.key())
                .tmforumBaseUrl(provider.tmforumBaseUrl())
                .selfDescription(provider.selfDescription())
                .clientId(provider.clientId())
                .scopes(provider.scopes());
    }

    /**
     * Converts a wire form to a stored provider under the given key.
     *
     * @param key      the key to store the provider under (the wire form's own key is ignored)
     * @param provider the wire form
     * @return the provider to store
     */
    static ProviderConfig toConfig(String key, ProviderVO provider) {
        return new ProviderConfig(key, provider.getTmforumBaseUrl(), provider.getSelfDescription(),
                provider.getClientId(), provider.getScopes());
    }
}
