package org.fiware.consent.provider;

import java.util.Objects;

/**
 * An identifier the facade mints, scoped to the provider whose TM Forum backend owns it.
 *
 * <p>A data space has many providers, so every id the facade puts into a contract, a catalog URL or
 * a participant self-description carries the {@link #providerKey() provider key} alongside the
 * backend-local id (multi-provider plan, {@code REQUIREMENTS.md} §11.4). This lets the facade route a
 * later request that carries the id back to the right provider without any external lookup: the
 * consent-manager round-trips whatever the facade encodes.
 *
 * <p>Wire form is a single path segment {@code providerKey~localId}, so the API paths keep a single
 * {@code {id}} variable (no OpenAPI change). {@code ~} is the separator because it is URL-safe
 * (RFC 3986 unreserved) and appears in neither provider keys (slugs) nor the {@code urn:ngsi-ld:…}
 * ids the FIWARE TM Forum API generates - unlike {@code :}, which those urns are full of. An
 * un-prefixed id (no {@code ~}) decodes to the {@link ProviderRegistry#DEFAULT_PROVIDER_KEY default}
 * provider, so ids minted before this scheme keep resolving.
 *
 * @param providerKey the provider key; must not contain the {@link #SEPARATOR}
 * @param localId     the id local to that provider's TM Forum backend
 */
public record ProviderScopedId(String providerKey, String localId) {

    /** Separator between the provider key and the backend-local id in the wire form. */
    public static final String SEPARATOR = "~";

    /**
     * @throws NullPointerException     if {@code providerKey} or {@code localId} is {@code null}
     * @throws IllegalArgumentException if {@code providerKey} contains the {@link #SEPARATOR}
     */
    public ProviderScopedId {
        Objects.requireNonNull(providerKey, "A provider-scoped id requires a provider key.");
        Objects.requireNonNull(localId, "A provider-scoped id requires a local id.");
        if (providerKey.contains(SEPARATOR)) {
            throw new IllegalArgumentException(
                    "A provider key must not contain the separator '" + SEPARATOR + "': " + providerKey);
        }
    }

    /**
     * @param providerKey the provider key
     * @param localId     the backend-local id
     * @return the provider-scoped id
     */
    public static ProviderScopedId of(String providerKey, String localId) {
        return new ProviderScopedId(providerKey, localId);
    }

    /**
     * Decodes a wire-form id. An id carrying the {@link #SEPARATOR} splits into its provider key and
     * local id (on the first separator, so local ids may themselves contain it); an id without the
     * separator is a backend-local id under the {@link ProviderRegistry#DEFAULT_PROVIDER_KEY default}
     * provider.
     *
     * @param encoded the wire-form id
     * @return the decoded provider-scoped id
     * @throws NullPointerException if {@code encoded} is {@code null}
     */
    public static ProviderScopedId decode(String encoded) {
        Objects.requireNonNull(encoded, "Cannot decode a null id.");
        int separatorIndex = encoded.indexOf(SEPARATOR);
        if (separatorIndex < 0) {
            return new ProviderScopedId(ProviderRegistry.DEFAULT_PROVIDER_KEY, encoded);
        }
        return new ProviderScopedId(
                encoded.substring(0, separatorIndex),
                encoded.substring(separatorIndex + SEPARATOR.length()));
    }

    /**
     * @return the wire-form id ({@code providerKey~localId})
     */
    public String encode() {
        return providerKey + SEPARATOR + localId;
    }
}
