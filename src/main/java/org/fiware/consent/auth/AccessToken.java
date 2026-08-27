package org.fiware.consent.auth;

import io.github.wistefan.oid4vp.model.TokenResponse;

import java.util.Optional;

/**
 * An OID4VP access token obtained from a verifier, together with the lifetime it may still be used
 * for.
 *
 * <p>Both token paths - the {@link Oid4VpTokenService} serving {@code POST /internal/tokens} and the
 * {@link Oid4VpAuthHandler} authenticating outbound TM Forum calls - hand their tokens to the shared
 * {@link Oid4VpTokenCache} in this form.
 *
 * @param value            the token to put in the {@code Authorization} header
 * @param tokenType        the OAuth2 token type, normally {@link #DEFAULT_TOKEN_TYPE}
 * @param expiresInSeconds the token's remaining lifetime in seconds
 */
public record AccessToken(String value, String tokenType, long expiresInSeconds) {

    /** The token type to report when the verifier does not state one. */
    public static final String DEFAULT_TOKEN_TYPE = "Bearer";

    /**
     * Reads a verifier's token response into an {@link AccessToken}.
     *
     * @param response the verifier's token response
     * @return the access token, or empty if the response carries no token
     */
    public static Optional<AccessToken> from(TokenResponse response) {
        if (response == null || response.getAccessToken() == null) {
            return Optional.empty();
        }
        return Optional.of(new AccessToken(
                response.getAccessToken(),
                Optional.ofNullable(response.getTokenType()).orElse(DEFAULT_TOKEN_TYPE),
                response.getExpiresIn()));
    }
}
