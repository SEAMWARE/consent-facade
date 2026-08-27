package org.fiware.consent.provider;

import java.util.Collection;
import java.util.Optional;

/**
 * Resolves a provider key into the {@link ProviderConfig} describing that provider's TM Forum
 * backend.
 *
 * <p>This is the abstraction the rest of the facade routes through so it can serve many providers
 * from one deployment (multi-provider design, {@code REQUIREMENTS.md} §11). Two implementations sit
 * behind it, selected by {@link #PERSISTENT_PROPERTY}: {@link StaticProviderRegistry} reads the
 * providers from configuration, {@link PersistentProviderRegistry} from a database that the admin API
 * can change at runtime (§11.8).
 *
 * <p>Every deployment has a {@link #DEFAULT_PROVIDER_KEY default} provider, which preserves the
 * single-provider behaviour: an un-prefixed id resolves to it (see
 * {@link ProviderScopedId#decode(String)}). Resolve it like any other, via
 * {@link #byKey(String) byKey(DEFAULT_PROVIDER_KEY)}.
 */
public interface ProviderRegistry {

    /**
     * Key of the default provider. Single-provider deployments configure only this entry, and an
     * un-prefixed id decodes to it.
     */
    String DEFAULT_PROVIDER_KEY = "default";

    /**
     * Configuration property selecting the database-backed {@link StaticProviderRegistry persistent}
     * registry over the {@link StaticProviderRegistry static} one (plan §11.8). When {@code true},
     * the {@code PersistentProviderRegistry} and the admin {@code /providers} API are active and a
     * {@code datasources.default} must be configured; otherwise the static, config-only registry is
     * used.
     */
    String PERSISTENT_PROPERTY = "facade.provider-registry.persistent";

    /**
     * Resolves a provider by its key.
     *
     * @param key the provider key
     * @return the matching provider config, or empty if no provider is registered under {@code key}
     */
    Optional<ProviderConfig> byKey(String key);

    /**
     * All registered providers.
     *
     * @return the registered provider configs (never {@code null}, possibly containing only the
     *         default)
     */
    Collection<ProviderConfig> all();
}
