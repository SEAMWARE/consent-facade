package org.fiware.consent.auth;

import io.github.wistefan.oid4vp.OID4VPClient;
import io.github.wistefan.oid4vp.config.RequestParameters;
import io.github.wistefan.oid4vp.model.TokenResponse;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link Oid4VpAuthHandler}: it authenticates only reactively on a {@code 401} — obtaining
 * an access token and retrying with a bearer token — and passes any other response through untouched.
 */
class Oid4VpAuthHandlerTest {

    private final OID4VPClient oid4VPClient = mock(OID4VPClient.class);
    private final Oid4VpAuthHandler handler = new Oid4VpAuthHandler(oid4VPClient);

    private static MutableHttpRequest<?> request() {
        MutableHttpRequest<Object> request = HttpRequest.GET("http://tmf.example:8080/tmf-api/party/v4/organization");
        request.setAttribute(Oid4VpAuthHandler.CLIENT_ID_ATTRIBUTE, "facade");
        request.setAttribute(Oid4VpAuthHandler.SCOPE_ATTRIBUTE, Set.of("tmforum"));
        return request;
    }

    @Test
    void passesThroughANonUnauthorizedResponseWithoutAuthenticating() {
        HttpResponse<?> response = handler.executeWithAuth(request(),
                anyRequest -> Mono.just(HttpResponse.ok("body"))).block();

        assertEquals(HttpStatus.OK, response.getStatus(), "A 2xx response is returned as-is.");
        verify(oid4VPClient, never()).getAccessToken(any());
    }

    @Test
    void authenticatesAndRetriesOnUnauthorized() {
        TokenResponse tokenResponse = mock(TokenResponse.class);
        when(tokenResponse.getAccessToken()).thenReturn("the-token");
        when(oid4VPClient.getAccessToken(any(RequestParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(tokenResponse));

        AtomicInteger attempts = new AtomicInteger();
        Function<MutableHttpRequest<?>, Mono<HttpResponse>> executor = req -> {
            if (attempts.getAndIncrement() == 0) {
                return Mono.just(HttpResponse.unauthorized());
            }
            assertEquals("Bearer the-token", req.getHeaders().get(HttpHeaders.AUTHORIZATION),
                    "The retry carries the obtained token as a bearer credential.");
            return Mono.just(HttpResponse.ok("data"));
        };

        HttpResponse<?> response = handler.executeWithAuth(request(), executor).block();

        assertEquals(HttpStatus.OK, response.getStatus(), "The authenticated retry succeeds.");
        assertEquals(2, attempts.get(), "The request is executed unauthenticated, then retried with the token.");
        verify(oid4VPClient).getAccessToken(any(RequestParameters.class));
    }
}
