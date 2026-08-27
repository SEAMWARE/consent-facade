package org.fiware.consent.auth;

import io.github.wistefan.oid4vp.OID4VPClient;
import io.github.wistefan.oid4vp.exception.AuthorizationException;
import io.github.wistefan.oid4vp.exception.AuthorizationRequestException;
import io.github.wistefan.oid4vp.exception.BadGatewayException;
import io.github.wistefan.oid4vp.exception.ClientResolutionException;
import io.github.wistefan.oid4vp.exception.CredentialsAccessException;
import io.github.wistefan.oid4vp.config.RequestParameters;
import io.github.wistefan.oid4vp.model.TokenResponse;
import org.fiware.consent.auth.Oid4VpConfiguration.TokenTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link Oid4VpTokenService}: audience resolution against the configured targets, the
 * per-audience caching it gets from the shared {@link Oid4VpTokenCache} and its refresh window, and
 * the translation of library failures into retryable/terminal reasons.
 */
class Oid4VpTokenServiceTest {

    private static final String AUDIENCE = "consent-manager";
    private static final long ONE_HOUR_SECONDS = 3600L;

    /** A clock the test moves forward explicitly, so expiry is tested without sleeping. */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-08-26T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    private static Oid4VpConfiguration configuration(TokenTarget... targets) {
        Oid4VpConfiguration configuration = new Oid4VpConfiguration();
        configuration.setEnabled(true);
        configuration.setTokenTargets(List.of(targets));
        return configuration;
    }

    private static TokenTarget target() {
        return new TokenTarget(AUDIENCE, URI.create("https://verifier.example.org"),
                "consent-manager", List.of("participant"), "/services/consent-manager");
    }

    private static TokenResponse tokenResponse(String value, long expiresIn) {
        return new TokenResponse().setAccessToken(value).setTokenType("Bearer").setExpiresIn(expiresIn);
    }

    @Test
    void rejectsAnAudienceThatIsNotConfigured() {
        Oid4VpTokenService service = new Oid4VpTokenService(
                mock(OID4VPClient.class), configuration(target()), new Oid4VpTokenCache(new MutableClock()));

        assertThrows(UnknownAudienceException.class, () -> service.tokenFor("something-else"),
                "only configured audiences may be presented to");
    }

    @Test
    void rejectsEveryAudienceWhenNoneIsConfigured() {
        Oid4VpTokenService service = new Oid4VpTokenService(
                mock(OID4VPClient.class), configuration(), new Oid4VpTokenCache(new MutableClock()));

        assertThrows(UnknownAudienceException.class, () -> service.tokenFor(AUDIENCE));
    }

    @Test
    void passesTheConfiguredDiscoveryPathToTheClient() {
        OID4VPClient client = mock(OID4VPClient.class);
        ArgumentCaptor<RequestParameters> captor = ArgumentCaptor.forClass(RequestParameters.class);
        when(client.getAccessToken(any()))
                .thenReturn(CompletableFuture.completedFuture(tokenResponse("token-1", ONE_HOUR_SECONDS)));

        new Oid4VpTokenService(client, configuration(target()), new Oid4VpTokenCache(new MutableClock())).tokenFor(AUDIENCE);

        verify(client).getAccessToken(captor.capture());
        // a VCVerifier serves discovery per service; the host root would 404
        assertEquals("/services/consent-manager", captor.getValue().path());
        assertEquals("consent-manager", captor.getValue().clientId());
        assertEquals(java.util.Set.of("participant"), captor.getValue().scope());
    }

    @Test
    void defaultsToTheWellKnownRootWhenNoDiscoveryPathIsConfigured() {
        OID4VPClient client = mock(OID4VPClient.class);
        ArgumentCaptor<RequestParameters> captor = ArgumentCaptor.forClass(RequestParameters.class);
        when(client.getAccessToken(any()))
                .thenReturn(CompletableFuture.completedFuture(tokenResponse("token-1", ONE_HOUR_SECONDS)));
        TokenTarget noPath = new TokenTarget(AUDIENCE, URI.create("https://idp.example.org"),
                null, null, null);

        new Oid4VpTokenService(client, configuration(noPath), new Oid4VpTokenCache(new MutableClock())).tokenFor(AUDIENCE);

        verify(client).getAccessToken(captor.capture());
        assertEquals("", captor.getValue().path());
    }

    @Test
    void returnsTheTokenFromTheVerifier() {
        OID4VPClient client = mock(OID4VPClient.class);
        when(client.getAccessToken(any()))
                .thenReturn(CompletableFuture.completedFuture(tokenResponse("token-1", ONE_HOUR_SECONDS)));

        AccessToken token = new Oid4VpTokenService(
                client, configuration(target()), new Oid4VpTokenCache(new MutableClock())).tokenFor(AUDIENCE);

        assertEquals("token-1", token.value());
        assertEquals("Bearer", token.tokenType());
    }

    @Test
    void servesASecondRequestFromTheCache() {
        OID4VPClient client = mock(OID4VPClient.class);
        when(client.getAccessToken(any()))
                .thenReturn(CompletableFuture.completedFuture(tokenResponse("token-1", ONE_HOUR_SECONDS)));
        Oid4VpTokenService service = new Oid4VpTokenService(client, configuration(target()), new Oid4VpTokenCache(new MutableClock()));

        assertEquals("token-1", service.tokenFor(AUDIENCE).value());
        assertEquals("token-1", service.tokenFor(AUDIENCE).value());

        verify(client, times(1)).getAccessToken(any());
    }

    @Test
    void refreshesBeforeTheTokenActuallyExpires() {
        OID4VPClient client = mock(OID4VPClient.class);
        when(client.getAccessToken(any()))
                .thenReturn(CompletableFuture.completedFuture(tokenResponse("token-1", ONE_HOUR_SECONDS)))
                .thenReturn(CompletableFuture.completedFuture(tokenResponse("token-2", ONE_HOUR_SECONDS)));
        MutableClock clock = new MutableClock();
        Oid4VpTokenService service = new Oid4VpTokenService(client, configuration(target()), new Oid4VpTokenCache(clock));

        assertEquals("token-1", service.tokenFor(AUDIENCE).value());
        // inside the refresh skew but not yet expired: the token must already be replaced, so the
        // caller never presents one that dies mid-flight
        clock.advance(Duration.ofSeconds(ONE_HOUR_SECONDS - 30));

        assertEquals("token-2", service.tokenFor(AUDIENCE).value());
        verify(client, times(2)).getAccessToken(any());
    }

    @Test
    void reportsTheRemainingLifetimeOfACachedToken() {
        OID4VPClient client = mock(OID4VPClient.class);
        when(client.getAccessToken(any()))
                .thenReturn(CompletableFuture.completedFuture(tokenResponse("token-1", ONE_HOUR_SECONDS)));
        MutableClock clock = new MutableClock();
        Oid4VpTokenService service = new Oid4VpTokenService(client, configuration(target()), new Oid4VpTokenCache(clock));

        service.tokenFor(AUDIENCE);
        clock.advance(Duration.ofMinutes(10));

        assertEquals(ONE_HOUR_SECONDS - Duration.ofMinutes(10).toSeconds(),
                service.tokenFor(AUDIENCE).expiresInSeconds(),
                "a cached token must not claim its original lifetime");
    }

    @Test
    void doesNotCacheATokenWithoutAUsableLifetime() {
        OID4VPClient client = mock(OID4VPClient.class);
        when(client.getAccessToken(any()))
                .thenReturn(CompletableFuture.completedFuture(tokenResponse("token-1", 0L)))
                .thenReturn(CompletableFuture.completedFuture(tokenResponse("token-2", 0L)));
        Oid4VpTokenService service = new Oid4VpTokenService(client, configuration(target()), new Oid4VpTokenCache(new MutableClock()));

        assertEquals("token-1", service.tokenFor(AUDIENCE).value());
        assertEquals("token-2", service.tokenFor(AUDIENCE).value());

        verify(client, times(2)).getAccessToken(any());
    }

    @Test
    void cachesAShortLivedTokenForPartOfItsLifetime() {
        OID4VPClient client = mock(OID4VPClient.class);
        when(client.getAccessToken(any()))
                .thenReturn(CompletableFuture.completedFuture(tokenResponse("token-1", 40L)))
                .thenReturn(CompletableFuture.completedFuture(tokenResponse("token-2", 40L)));
        MutableClock clock = new MutableClock();
        Oid4VpTokenService service = new Oid4VpTokenService(client, configuration(target()), new Oid4VpTokenCache(clock));

        // a 40s token cannot be cached for "lifetime - 60s skew"; half its life is used instead
        assertEquals(40L, service.tokenFor(AUDIENCE).expiresInSeconds());
        assertEquals("token-1", service.tokenFor(AUDIENCE).value());
        clock.advance(Duration.ofSeconds(10));
        assertEquals("token-1", service.tokenFor(AUDIENCE).value());
        // The reported lifetime must come from the token's real expiry. Reconstructing it from the
        // staleness deadline (which is only "expiry - skew" for a long-lived token) reported 70s here
        // for a token that dies in 30 - and the consent-plugin caches on that number.
        assertEquals(30L, service.tokenFor(AUDIENCE).expiresInSeconds(),
                "a short-lived cached token must not over-report its remaining lifetime");
        clock.advance(Duration.ofSeconds(15));
        assertEquals("token-2", service.tokenFor(AUDIENCE).value());
    }

    @Test
    void neverReportsMoreLifetimeThanTheTokenActuallyHas() {
        OID4VPClient client = mock(OID4VPClient.class);
        when(client.getAccessToken(any()))
                .thenReturn(CompletableFuture.completedFuture(tokenResponse("token-1", 90L)));
        MutableClock clock = new MutableClock();
        Oid4VpTokenService service = new Oid4VpTokenService(client, configuration(target()), new Oid4VpTokenCache(clock));

        // 90s lifetime, 60s skew: cached for 30s, so the last hit lands just before the deadline
        service.tokenFor(AUDIENCE);
        clock.advance(Duration.ofSeconds(29));

        assertEquals(61L, service.tokenFor(AUDIENCE).expiresInSeconds(),
                "the reported lifetime is what is left until the real expiry");
    }

    @Test
    void treatsAResponseWithoutATokenAsRetryable() {
        OID4VPClient client = mock(OID4VPClient.class);
        when(client.getAccessToken(any()))
                .thenReturn(CompletableFuture.completedFuture(new TokenResponse()));
        Oid4VpTokenService service = new Oid4VpTokenService(client, configuration(target()), new Oid4VpTokenCache(new MutableClock()));

        TokenAcquisitionException exception =
                assertThrows(TokenAcquisitionException.class, () -> service.tokenFor(AUDIENCE));
        assertEquals(TokenAcquisitionException.Reason.VERIFIER_UNREACHABLE, exception.getReason());
    }

    private static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(new BadGatewayException("verifier down"),
                        TokenAcquisitionException.Reason.VERIFIER_UNREACHABLE),
                Arguments.of(new AuthorizationException("token endpoint said no"),
                        TokenAcquisitionException.Reason.CREDENTIAL_REJECTED),
                Arguments.of(new AuthorizationRequestException("no request to resolve"),
                        TokenAcquisitionException.Reason.CREDENTIAL_REJECTED),
                Arguments.of(new ClientResolutionException("invalid authorization request"),
                        TokenAcquisitionException.Reason.CREDENTIAL_REJECTED),
                Arguments.of(new CredentialsAccessException("cannot read the credential"),
                        TokenAcquisitionException.Reason.MISCONFIGURED),
                Arguments.of(new IllegalStateException("something else entirely"),
                        TokenAcquisitionException.Reason.MISCONFIGURED));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void translatesLibraryFailuresIntoReasons(RuntimeException thrown,
                                              TokenAcquisitionException.Reason expected) {
        OID4VPClient client = mock(OID4VPClient.class);
        when(client.getAccessToken(any())).thenReturn(CompletableFuture.failedFuture(thrown));
        Oid4VpTokenService service = new Oid4VpTokenService(client, configuration(target()), new Oid4VpTokenCache(new MutableClock()));

        TokenAcquisitionException exception =
                assertThrows(TokenAcquisitionException.class, () -> service.tokenFor(AUDIENCE));
        assertEquals(expected, exception.getReason());
    }

    @Test
    void doesNotCacheAFailure() {
        OID4VPClient client = mock(OID4VPClient.class);
        when(client.getAccessToken(any()))
                .thenReturn(CompletableFuture.failedFuture(new BadGatewayException("verifier down")))
                .thenReturn(CompletableFuture.completedFuture(tokenResponse("token-1", ONE_HOUR_SECONDS)));
        Oid4VpTokenService service = new Oid4VpTokenService(client, configuration(target()), new Oid4VpTokenCache(new MutableClock()));

        assertThrows(TokenAcquisitionException.class, () -> service.tokenFor(AUDIENCE));
        assertEquals("token-1", service.tokenFor(AUDIENCE).value(),
                "a transient failure must not poison the cache");
    }

    @Test
    void coalescesConcurrentMissesOntoOnePresentation() throws Exception {
        int callers = 16;
        AtomicInteger presentations = new AtomicInteger();
        OID4VPClient client = mock(OID4VPClient.class);
        when(client.getAccessToken(any())).thenAnswer(invocation -> {
            presentations.incrementAndGet();
            // hold the exchange open long enough for the other callers to pile up behind it
            Thread.sleep(50);
            return CompletableFuture.completedFuture(tokenResponse("token-1", ONE_HOUR_SECONDS));
        });
        Oid4VpTokenService service = new Oid4VpTokenService(client, configuration(target()), new Oid4VpTokenCache(new MutableClock()));

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        try {
            for (int i = 0; i < callers; i++) {
                executor.submit(() -> {
                    try {
                        start.await();
                        service.tokenFor(AUDIENCE);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "all callers finished");
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, presentations.get(),
                "a burst of callers must cost one exchange with the verifier, not one each");
    }
}
