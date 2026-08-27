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

import io.github.wistefan.oid4vp.OID4VPClient;
import io.github.wistefan.oid4vp.exception.AuthorizationException;
import io.github.wistefan.oid4vp.exception.BadGatewayException;
import io.github.wistefan.oid4vp.exception.CredentialsAccessException;
import io.github.wistefan.oid4vp.model.TokenResponse;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link InternalTokenController}: the internal endpoint that hands OID4VP access tokens
 * to components which do not implement OID4VP (the consent-plugin). Covers the audience allow-list
 * and the status mapping the caller depends on to tell a retryable failure from a terminal one.
 */
// rebuildContext: the token service caches per audience by design, so tests must not
// inherit a cached token from an earlier one
@MicronautTest(rebuildContext = true)
@Property(name = "oid4vp.enabled", value = "true")
@Property(name = "oid4vp.token-targets[0].audience", value = "consent-manager")
@Property(name = "oid4vp.token-targets[0].url", value = "https://verifier.dataspace-authority.org")
@Property(name = "oid4vp.token-targets[0].client-id", value = "consent-manager")
@Property(name = "oid4vp.token-targets[0].scope[0]", value = "participant")
class InternalTokenControllerTest {

    private static final String TOKENS_PATH = "/internal/tokens";

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    OID4VPClient oid4VPClient;

    @MockBean(OID4VPClient.class)
    OID4VPClient oid4VPClient() {
        return mock(OID4VPClient.class);
    }

    private HttpRequest<?> tokenRequest(Object body) {
        return HttpRequest.POST(TOKENS_PATH, body);
    }

    private HttpStatus statusOf(Object body) {
        try {
            return client.toBlocking().exchange(tokenRequest(body)).getStatus();
        } catch (HttpClientResponseException exception) {
            return exception.getStatus();
        }
    }

    @Test
    void handsOutATokenForAConfiguredAudience() {
        when(oid4VPClient.getAccessToken(any())).thenReturn(CompletableFuture.completedFuture(
                new TokenResponse().setAccessToken("the-token").setTokenType("Bearer").setExpiresIn(3600L)));

        Map<?, ?> body = client.toBlocking().retrieve(
                tokenRequest(Map.of("audience", "consent-manager")), Map.class);

        assertEquals("the-token", body.get("access_token"), "the token is returned OAuth2-shaped");
        assertEquals("Bearer", body.get("token_type"));
        assertEquals(3600, ((Number) body.get("expires_in")).longValue());
    }

    @Test
    void rejectsAnAudienceThatIsNotConfigured() {
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(Map.of("audience", "some-other-verifier")),
                "the facade must only present its credential to configured audiences");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void rejectsABlankAudience(String audience) {
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(Map.of("audience", audience)));
    }

    @Test
    void rejectsARequestWithoutAnAudience() {
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(Map.of()));
    }

    private static Stream<Arguments> failureStatuses() {
        return Stream.of(
                // retryable: the verifier was not reachable, so the caller should try again
                Arguments.of(new BadGatewayException("verifier down"), HttpStatus.BAD_GATEWAY),
                // terminal: the credential was refused, retrying changes nothing
                Arguments.of(new AuthorizationException("refused"), HttpStatus.FORBIDDEN),
                // terminal: this facade's own OID4VP setup is broken
                Arguments.of(new CredentialsAccessException("no credential file"),
                        HttpStatus.INTERNAL_SERVER_ERROR));
    }

    @ParameterizedTest
    @MethodSource("failureStatuses")
    void mapsFailuresToStatusesTheCallerCanActOn(RuntimeException thrown, HttpStatus expected) {
        when(oid4VPClient.getAccessToken(any())).thenReturn(CompletableFuture.failedFuture(thrown));

        assertEquals(expected, statusOf(Map.of("audience", "consent-manager")));
    }

    @Test
    void doesNotExposeTheTokenPathOnAGet() {
        assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(HttpRequest.GET(TOKENS_PATH)));
    }
}
