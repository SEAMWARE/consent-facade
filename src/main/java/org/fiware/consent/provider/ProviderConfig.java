package org.fiware.consent.provider;

import java.util.Objects;

/**
 * Immutable description of a provider known to this facade and the TM Forum backend that serves its
 * catalog, agreements and parties.
 *
 * <p>A data space has many providers, each with its own {@code tm-forum-api}; this facade routes
 * every request to the right one by the {@link #key() provider key} carried in the identifiers it
 * mints (multi-provider plan, {@code REQUIREMENTS.md} §11). This value is the resolved result of a
 * {@link ProviderRegistry} lookup.
 *
 * @param key            the URL-safe, stable key identifying the provider (part of every
 *                       facade-minted identifier); never {@code null}
 * @param tmforumBaseUrl base url of the provider's TM Forum backend (e.g.
 *                       {@code http://tm-forum-api.provider.svc.cluster.local:8080}); consumed once
 *                       per-provider client routing is wired (plan §11.5)
 */
public record ProviderConfig(String key, String tmforumBaseUrl) {

    /**
     * Creates a provider config.
     *
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public ProviderConfig {
        Objects.requireNonNull(key, "A provider config requires a key.");
    }
}
