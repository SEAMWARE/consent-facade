package org.fiware.consent.provider;

import io.micronaut.core.annotation.Nullable;

import java.util.List;
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
 * @param key             the URL-safe, stable key identifying the provider (part of every
 *                        facade-minted identifier); never {@code null}
 * @param tmforumBaseUrl  base url of the provider's TM Forum backend (e.g.
 *                        {@code http://tm-forum-api.provider.svc.cluster.local:8080}); consumed by
 *                        per-provider client routing (plan §11.5)
 * @param selfDescription this provider participant's own self-description URL (provider-keyed, e.g.
 *                        {@code …/participants/{key}~{providerOrgId}}), used as the {@code producedBy}
 *                        of the data resources it offers (plan §11.7); may be {@code null}, in which
 *                        case callers fall back to the legacy global {@code facade.provider.self-description}
 * @param clientId        this provider's OID4VP {@code client_id} for authenticating outbound TM Forum
 *                        calls (implementation-plan.md step 4); {@code null} ⇒ the facade default
 *                        {@code oid4vp.client-id}
 * @param scopes          this provider's OID4VP scopes; {@code null}/empty ⇒ the facade default
 *                        {@code oid4vp.scopes}
 */
public record ProviderConfig(String key, String tmforumBaseUrl, String selfDescription,
                             @Nullable String clientId, @Nullable List<String> scopes) {

    /**
     * Creates a provider config.
     *
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public ProviderConfig {
        Objects.requireNonNull(key, "A provider config requires a key.");
    }
}
