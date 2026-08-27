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
