package org.fiware.consent.auth;

import io.github.wistefan.oid4vp.model.TokenResponse;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Oid4VpTokenCache}, the single place that decides when a token is stale and how much
 * lifetime is left on it.
 */
class Oid4VpTokenCacheTest {

    private static final long ONE_HOUR_SECONDS = 3600L;
    private static final Duration MAX_WAIT = Duration.ofSeconds(5);

    private static final Oid4VpTokenCache.Key AUDIENCE_KEY = Oid4VpTokenCache.Key.forAudience("consent-manager");

    /** A clock the test moves forward explicitly, so expiry is tested without sleeping. */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-08-27T10:00:00Z");

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

    private static AccessToken token(String value, long expiresIn) {
        return new AccessToken(value, AccessToken.DEFAULT_TOKEN_TYPE, expiresIn);
    }

    @Test
    void lookup_isNullForAKeyThatWasNeverStored() {
        assertNull(new Oid4VpTokenCache().lookup(AUDIENCE_KEY));
    }

    @Test
    void store_thenLookup_reportsTheRemainingLifetime() {
        MutableClock clock = new MutableClock();
        Oid4VpTokenCache cache = new Oid4VpTokenCache(clock);

        cache.store(AUDIENCE_KEY, token("t", ONE_HOUR_SECONDS));
        clock.advance(Duration.ofMinutes(5));

        assertEquals(ONE_HOUR_SECONDS - 300, cache.lookup(AUDIENCE_KEY).expiresInSeconds());
    }

    @Test
    void lookup_isNullOnceTheTokenIsStale() {
        MutableClock clock = new MutableClock();
        Oid4VpTokenCache cache = new Oid4VpTokenCache(clock);

        cache.store(AUDIENCE_KEY, token("t", ONE_HOUR_SECONDS));
        // staleAt sits a 60s skew before the real expiry, so the token is dropped early on purpose
        clock.advance(Duration.ofSeconds(ONE_HOUR_SECONDS - 59));

        assertNull(cache.lookup(AUDIENCE_KEY), "a token inside the refresh skew is no longer handed out");
    }

    @Test
    void invalidate_dropsACachedToken() {
        Oid4VpTokenCache cache = new Oid4VpTokenCache();
        cache.store(AUDIENCE_KEY, token("t", ONE_HOUR_SECONDS));

        cache.invalidate(AUDIENCE_KEY);

        assertNull(cache.lookup(AUDIENCE_KEY));
    }

    @Test
    void invalidate_isANoOpForAnUnknownKey() {
        Oid4VpTokenCache cache = new Oid4VpTokenCache();

        cache.invalidate(AUDIENCE_KEY);

        assertNull(cache.lookup(AUDIENCE_KEY), "the cache stays usable");
    }

    @Test
    void getOrLoad_returnsTheCachedTokenWithoutCallingTheLoader() {
        Oid4VpTokenCache cache = new Oid4VpTokenCache();
        cache.store(AUDIENCE_KEY, token("cached", ONE_HOUR_SECONDS));

        AccessToken loaded = cache.getOrLoad(AUDIENCE_KEY, MAX_WAIT, () -> {
            throw new AssertionError("the loader must not run on a hit");
        });

        assertEquals("cached", loaded.value());
    }

    @Test
    void getOrLoad_propagatesTheLoadersFailureAndDoesNotCacheIt() {
        Oid4VpTokenCache cache = new Oid4VpTokenCache();
        AtomicInteger attempts = new AtomicInteger();

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThrows(TokenAcquisitionException.class, () -> cache.getOrLoad(AUDIENCE_KEY, MAX_WAIT, () -> {
                attempts.incrementAndGet();
                throw new TokenAcquisitionException(
                        TokenAcquisitionException.Reason.VERIFIER_UNREACHABLE, "nope", null);
            }));
        }

        assertEquals(2, attempts.get(), "a failed exchange must not be remembered as a result");
    }

    @Test
    void getOrLoad_coalescesConcurrentMissesOntoOneExchange() throws Exception {
        Oid4VpTokenCache cache = new Oid4VpTokenCache();
        int callers = 8;
        AtomicInteger exchanges = new AtomicInteger();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            for (int caller = 0; caller < callers; caller++) {
                pool.submit(() -> {
                    cache.getOrLoad(AUDIENCE_KEY, MAX_WAIT, () -> {
                        exchanges.incrementAndGet();
                        loaderEntered.countDown();
                        try {
                            releaseLoader.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return token("shared", ONE_HOUR_SECONDS);
                    });
                    done.countDown();
                    return null;
                });
            }
            assertTrue(loaderEntered.await(5, TimeUnit.SECONDS), "the first caller must reach the loader");
            releaseLoader.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "every caller must get an answer");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, exchanges.get(), "a burst for one key costs one presentation, not one per caller");
        assertEquals("shared", cache.lookup(AUDIENCE_KEY).value());
    }

    @Test
    void aTokenWithoutAUsableLifetimeIsNotCached() {
        Oid4VpTokenCache cache = new Oid4VpTokenCache();

        cache.store(AUDIENCE_KEY, token("already-expired", 0L));

        assertNull(cache.lookup(AUDIENCE_KEY));
    }

    @Test
    void serviceKeys_distinguishEveryPartOfTheTargetIdentity() {
        URI service = URI.create("http://tmf.example:8080");
        Oid4VpTokenCache.Key base = Oid4VpTokenCache.Key.forService(service, "facade", Set.of("tmforum"));

        assertNotEquals(base, Oid4VpTokenCache.Key.forService(
                URI.create("http://other.example:8080"), "facade", Set.of("tmforum")));
        assertNotEquals(base, Oid4VpTokenCache.Key.forService(service, "someone-else", Set.of("tmforum")));
        assertNotEquals(base, Oid4VpTokenCache.Key.forService(service, "facade", Set.of("tmforum", "extra")));
        assertNotEquals(base, Oid4VpTokenCache.Key.forAudience("facade"));
    }

    @Test
    void serviceKeys_doNotDependOnScopeIterationOrder() {
        URI service = URI.create("http://tmf.example:8080");

        assertEquals(
                Oid4VpTokenCache.Key.forService(service, "facade", Set.of("a", "b")),
                Oid4VpTokenCache.Key.forService(service, "facade", Set.of("b", "a")),
                "the same scope set must hit the same cache entry whatever order it iterates in");
    }

    @Test
    void differentKeysAreCachedIndependently() {
        Oid4VpTokenCache cache = new Oid4VpTokenCache();
        Oid4VpTokenCache.Key other = Oid4VpTokenCache.Key.forAudience("other");

        cache.store(AUDIENCE_KEY, token("first", ONE_HOUR_SECONDS));
        cache.store(other, token("second", ONE_HOUR_SECONDS));
        cache.invalidate(AUDIENCE_KEY);

        assertNull(cache.lookup(AUDIENCE_KEY));
        assertEquals("second", cache.lookup(other).value());
    }

    @Test
    void theTokenTypeIsCarriedThrough() {
        Oid4VpTokenCache cache = new Oid4VpTokenCache();
        cache.store(AUDIENCE_KEY, new AccessToken("t", "DPoP", ONE_HOUR_SECONDS));

        assertEquals("DPoP", cache.lookup(AUDIENCE_KEY).tokenType());
    }

    @Test
    void from_isEmptyForAResponseWithoutAToken() {
        assertTrue(AccessToken.from(null).isEmpty());
        assertTrue(AccessToken.from(new TokenResponse()).isEmpty());
    }

    @Test
    void from_defaultsTheTokenTypeWhenTheVerifierStatesNone() {
        AccessToken accessToken = AccessToken.from(
                new TokenResponse().setAccessToken("t").setExpiresIn(60L))
                .orElseThrow();

        assertEquals(AccessToken.DEFAULT_TOKEN_TYPE, accessToken.tokenType());
        assertEquals("t", accessToken.value());
    }
}
