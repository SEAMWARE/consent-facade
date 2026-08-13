package org.fiware.consent.provider;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;

/**
 * Binds a single {@code facade.providers.<key>} configuration entry.
 *
 * <p>Micronaut creates one bean per configured provider key (the map key becomes the
 * {@link #getKey() provider key}); {@link StaticProviderRegistry} aggregates them into immutable
 * {@link ProviderConfig} values. This is the static, configuration-driven source of the provider
 * registry (multi-provider plan, {@code REQUIREMENTS.md} §11.3); a database-backed source (§11.8)
 * replaces it later behind the same {@link ProviderRegistry} interface.
 *
 * <p>Example configuration:
 * <pre>
 * facade:
 *   providers:
 *     default:
 *       tmforum-base-url: http://tm-forum-api.provider.svc.cluster.local:8080
 * </pre>
 */
@EachProperty("facade.providers")
public class ProviderConfiguration {

    private final String key;
    private String tmforumBaseUrl;

    /**
     * Creates the configuration for one provider.
     *
     * @param key the provider key (the {@code facade.providers.<key>} map key)
     */
    public ProviderConfiguration(@Parameter String key) {
        this.key = key;
    }

    /**
     * @return the provider key
     */
    public String getKey() {
        return key;
    }

    /**
     * @return base url of the provider's TM Forum backend
     */
    public String getTmforumBaseUrl() {
        return tmforumBaseUrl;
    }

    /**
     * @param tmforumBaseUrl base url of the provider's TM Forum backend
     */
    public void setTmforumBaseUrl(String tmforumBaseUrl) {
        this.tmforumBaseUrl = tmforumBaseUrl;
    }
}
