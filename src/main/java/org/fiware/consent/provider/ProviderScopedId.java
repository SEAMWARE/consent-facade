/*
 * Copyright 2026 Seamless Middleware Technologies S.L and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fiware.consent.provider;

import org.fiware.consent.exception.InvalidIdentifierException;

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
 * <p>Wire-form ids arrive as public path variables and end up in the facade's outbound TM Forum
 * request path, so {@link #decode(String)} rejects the characters that would let a caller shape that
 * request rather than just name a resource (see {@link #ILLEGAL_ID_CHARACTERS}).
 *
 * @param providerKey the provider key; must not contain the {@link #SEPARATOR}
 * @param localId     the id local to that provider's TM Forum backend
 */
public record ProviderScopedId(String providerKey, String localId) {

    /** Separator between the provider key and the backend-local id in the wire form. */
    public static final String SEPARATOR = "~";

    /**
     * Characters a wire-form id must not contain. Each of them lets a caller do more than name a
     * resource in the outbound TM Forum path: {@code /} leaves the path segment, {@code ?} starts a
     * query, {@code #} truncates the rest as a fragment, and {@code \\} is normalised to {@code /} by
     * some servers.
     */
    private static final String ILLEGAL_ID_CHARACTERS = "/?#\\";

    /** The relative path segment a caller could use to walk up the outbound path. */
    private static final String PARENT_SEGMENT = "..";

    /** Highest code point treated as a control character (C0 controls plus DEL). */
    private static final int LAST_CONTROL_CHARACTER = 0x1F;
    private static final int DELETE_CHARACTER = 0x7F;

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
     * @throws NullPointerException        if {@code encoded} is {@code null}
     * @throws InvalidIdentifierException if {@code encoded} is blank or carries a character that
     *                                    would let the caller shape the outbound request path
     */
    public static ProviderScopedId decode(String encoded) {
        Objects.requireNonNull(encoded, "Cannot decode a null id.");
        requireRoutableId(encoded);
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

    /**
     * Rejects a wire-form id that would not be safe to interpolate into the outbound TM Forum path.
     * The messages name only the rule that was broken - they are returned to the caller.
     */
    private static void requireRoutableId(String encoded) {
        if (encoded.isBlank()) {
            throw new InvalidIdentifierException("An id must not be blank.");
        }
        for (char illegalCharacter : ILLEGAL_ID_CHARACTERS.toCharArray()) {
            if (encoded.indexOf(illegalCharacter) >= 0) {
                throw new InvalidIdentifierException(
                        "An id must not contain '" + illegalCharacter + "'.");
            }
        }
        if (encoded.contains(PARENT_SEGMENT)) {
            throw new InvalidIdentifierException("An id must not contain '" + PARENT_SEGMENT + "'.");
        }
        if (encoded.chars().anyMatch(character ->
                character <= LAST_CONTROL_CHARACTER || character == DELETE_CHARACTER)) {
            throw new InvalidIdentifierException("An id must not contain control characters.");
        }
    }
}
