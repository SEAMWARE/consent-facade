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

import java.net.URI;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link Oid4VpAuthHandler}: it attaches a token cached for the target service up front,
 * presents the credential only when there is none or the target refuses one, and passes any
 * non-{@code 401} response through untouched.
 */
class Oid4VpAuthHandlerTest {

    private static final long ONE_HOUR_SECONDS = 3600L;

    private final OID4VPClient oid4VPClient = mock(OID4VPClient.class);
    private final Oid4VpTokenCache tokenCache = new Oid4VpTokenCache();
    private final Oid4VpAuthHandler handler =
            new Oid4VpAuthHandler(oid4VPClient, tokenCache, new Oid4VpConfiguration());

    private static MutableHttpRequest<?> request() {
        MutableHttpRequest<Object> request = HttpRequest.GET("http://tmf.example:8080/tmf-api/party/v4/organization");
        request.setAttribute(Oid4VpAuthHandler.CLIENT_ID_ATTRIBUTE, "facade");
        request.setAttribute(Oid4VpAuthHandler.SCOPE_ATTRIBUTE, Set.of("tmforum"));
        return request;
    }

    private static TokenResponse tokenResponse(String value, long expiresIn) {
        return new TokenResponse().setAccessToken(value).setTokenType("Bearer").setExpiresIn(expiresIn);
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
        when(oid4VPClient.getAccessToken(any(RequestParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(tokenResponse("the-token", ONE_HOUR_SECONDS)));

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

    @Test
    void attachesACachedTokenUpFrontSoTheSecondCallCostsNoPresentation() {
        when(oid4VPClient.getAccessToken(any(RequestParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(tokenResponse("the-token", ONE_HOUR_SECONDS)));
        AtomicInteger attempts = new AtomicInteger();
        Function<MutableHttpRequest<?>, Mono<HttpResponse>> executor = req -> {
            if (req.getHeaders().get(HttpHeaders.AUTHORIZATION) == null) {
                attempts.incrementAndGet();
                return Mono.just(HttpResponse.unauthorized());
            }
            attempts.incrementAndGet();
            return Mono.just(HttpResponse.ok("data"));
        };

        handler.executeWithAuth(request(), executor).block();
        HttpResponse<?> second = handler.executeWithAuth(request(), executor).block();

        assertEquals(HttpStatus.OK, second.getStatus());
        assertEquals(3, attempts.get(),
                "the first call costs an unauthorized round trip plus a retry, the second only one authenticated call");
        verify(oid4VPClient, times(1)).getAccessToken(any(RequestParameters.class));
    }

    @Test
    void dropsAndReplacesACachedTokenTheTargetRefuses() {
        when(oid4VPClient.getAccessToken(any(RequestParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(tokenResponse("stale-token", ONE_HOUR_SECONDS)))
                .thenReturn(CompletableFuture.completedFuture(tokenResponse("fresh-token", ONE_HOUR_SECONDS)));
        // seed the cache the way a successful call would
        handler.executeWithAuth(request(), req -> req.getHeaders().get(HttpHeaders.AUTHORIZATION) == null
                ? Mono.just(HttpResponse.unauthorized())
                : Mono.just(HttpResponse.ok("data"))).block();

        AtomicInteger attempts = new AtomicInteger();
        Function<MutableHttpRequest<?>, Mono<HttpResponse>> refusesTheStaleToken = req -> {
            String authorization = req.getHeaders().get(HttpHeaders.AUTHORIZATION);
            attempts.incrementAndGet();
            return "Bearer fresh-token".equals(authorization)
                    ? Mono.just(HttpResponse.ok("data"))
                    : Mono.just(HttpResponse.unauthorized());
        };

        HttpResponse<?> response = handler.executeWithAuth(request(), refusesTheStaleToken).block();

        assertEquals(HttpStatus.OK, response.getStatus(), "a refused token is replaced, not reused");
        assertEquals(2, attempts.get(), "the refusal costs one retry, not a loop");
        verify(oid4VPClient, times(2)).getAccessToken(any(RequestParameters.class));
    }

    @Test
    void doesNotCacheATokenWithoutAUsableLifetime() {
        when(oid4VPClient.getAccessToken(any(RequestParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(tokenResponse("the-token", 0L)));

        handler.executeWithAuth(request(), req -> req.getHeaders().get(HttpHeaders.AUTHORIZATION) == null
                ? Mono.just(HttpResponse.unauthorized())
                : Mono.just(HttpResponse.ok("data"))).block();

        assertNull(tokenCache.lookup(Oid4VpTokenCache.Key.forService(
                        URI.create("http://tmf.example:8080"), "facade", Set.of("tmforum"))),
                "a token the verifier reports as already expired must not be handed to the next call");
    }
}
