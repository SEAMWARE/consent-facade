package org.fiware.consent.provider;

import java.util.Collection;
import java.util.Optional;

/**
 * Resolves a provider key into the {@link ProviderConfig} describing that provider's TM Forum
 * backend.
 *
 * <p>This is the abstraction the rest of the facade routes through so it can serve many providers
 * from one deployment (multi-provider plan, {@code REQUIREMENTS.md} §11). The interface is stable
 * across implementations: a {@link StaticProviderRegistry} backed by configuration is the starting
 * point, and a database-backed registry with an admin API (plan §11.8) is a later drop-in
 * replacement.
 *
 * <p>Every deployment has a {@link #DEFAULT_PROVIDER_KEY default} provider, which preserves the
 * single-provider behaviour and serves as the fallback until per-request routing (plan §11.6) is
 * wired.
 */
public interface ProviderRegistry {

    /**
     * Key of the default provider. Single-provider deployments configure only this entry, and it is
     * the fallback used before provider-keyed routing is in place.
     */
    String DEFAULT_PROVIDER_KEY = "default";

    /**
     * Resolves a provider by its key.
     *
     * @param key the provider key
     * @return the matching provider config, or empty if no provider is registered under {@code key}
     */
    Optional<ProviderConfig> byKey(String key);

    /**
     * The default provider (registered under {@link #DEFAULT_PROVIDER_KEY}).
     *
     * @return the default provider config
     */
    ProviderConfig defaultProvider();

    /**
     * All registered providers.
     *
     * @return the registered provider configs (never {@code null}, possibly containing only the
     *         default)
     */
    Collection<ProviderConfig> all();
}
