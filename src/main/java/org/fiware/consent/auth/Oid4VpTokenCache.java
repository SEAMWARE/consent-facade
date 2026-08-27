package org.fiware.consent.auth;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The facade's single cache for OID4VP access tokens.
 *
 * <p>Both token paths share it, so there is one place that decides when a token is stale and one
 * answer to "how long is this token still good for":
 *
 * <ul>
 *   <li>{@link Oid4VpTokenService}, serving {@code POST /internal/tokens}, caches per configured
 *       {@link Key#forAudience(String) audience};</li>
 *   <li>{@link Oid4VpAuthHandler}, authenticating the facade's outbound TM Forum calls, caches per
 *       {@link Key#forService(URI, String, Set) target service} - without which every outbound call
 *       would cost an unauthorized round trip plus a full verifiable presentation.</li>
 * </ul>
 *
 * <p>A cached token is handed out until {@link Entry#staleAt}, which sits {@link #REFRESH_SKEW}
 * before its actual expiry so a caller always has usable time left. The lifetime reported to the
 * caller is derived from the token's <em>actual</em> expiry, never reconstructed from the staleness
 * deadline: the {@code expires_in} the internal API documents is a number external callers cache on,
 * so over-reporting it makes them present a dead token.
 *
 * <p>Concurrent misses for the same key are coalesced onto a single exchange with the verifier, and
 * that exchange runs <em>outside</em> any lock: an unresponsive verifier must not be able to pin
 * blocking threads on a monitor.
 */
@Singleton
@Requires(condition = Oid4VpConfiguration.Oid4VpCondition.class)
@Slf4j
public class Oid4VpTokenCache {

    /**
     * How long before actual expiry a cached token is considered stale. Covers the clock skew
     * between facade and verifier plus the time the caller still needs the token to be valid for.
     */
    static final Duration REFRESH_SKEW = Duration.ofSeconds(60);

    /**
     * Divisor applied when a token lives no longer than {@link #REFRESH_SKEW}: such a token cannot
     * be cached for {@code expiresIn - skew} (that is not positive), so half its lifetime is used.
     */
    private static final int SHORT_LIVED_TTL_DIVISOR = 2;

    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();
    private final Map<Key, CompletableFuture<AccessToken>> exchangesInFlight = new ConcurrentHashMap<>();
    private final Clock clock;

    /** Creates the cache on the system UTC clock. */
    public Oid4VpTokenCache() {
        this(Clock.systemUTC());
    }

    /**
     * Creates the cache with an explicit clock.
     *
     * @param clock the clock used to decide staleness and remaining lifetime
     */
    Oid4VpTokenCache(Clock clock) {
        this.clock = clock;
    }

    /**
     * Returns the cached token for a key when one is still fresh.
     *
     * @param key the cache key
     * @return the cached token with its actual remaining lifetime, or {@code null} if none is cached
     *         or the cached one is stale
     */
    public AccessToken lookup(Key key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        synchronized (entry) {
            return entry.valid(clock.instant());
        }
    }

    /**
     * Returns the cached token for a key, obtaining a fresh one through {@code loader} on a miss.
     *
     * <p>Concurrent misses for the same key are coalesced: the first caller runs {@code loader}, the
     * others wait for its result (at most {@code maxWait}) instead of starting their own exchange.
     * {@code loader} runs outside any lock the cache holds.
     *
     * @param key     the cache key
     * @param maxWait how long a coalesced caller waits for the in-flight exchange
     * @param loader  obtains a fresh token; must itself be bounded in time
     * @return the cached or freshly obtained token
     * @throws TokenAcquisitionException if the exchange failed, or did not finish within {@code maxWait}
     */
    public AccessToken getOrLoad(Key key, Duration maxWait, Supplier<AccessToken> loader) {
        AccessToken cached = lookup(key);
        if (cached != null) {
            return cached;
        }
        CompletableFuture<AccessToken> ourExchange = new CompletableFuture<>();
        CompletableFuture<AccessToken> runningExchange = exchangesInFlight.putIfAbsent(key, ourExchange);
        if (runningExchange != null) {
            return awaitCoalesced(key, runningExchange, maxWait);
        }
        try {
            AccessToken fresh = loader.get();
            store(key, fresh);
            ourExchange.complete(fresh);
            return fresh;
        } catch (RuntimeException | Error failure) {
            ourExchange.completeExceptionally(failure);
            throw failure;
        } finally {
            // Guarantees coalesced callers are released whatever path this method left by - including
            // one neither branch above covers.
            if (!ourExchange.isDone()) {
                ourExchange.completeExceptionally(new TokenAcquisitionException(
                        TokenAcquisitionException.Reason.VERIFIER_UNREACHABLE,
                        "The token exchange for %s was abandoned.".formatted(key.value()), null));
            }
            exchangesInFlight.remove(key, ourExchange);
        }
    }

    /**
     * Caches a token. A token without a usable lifetime is not cached at all.
     *
     * @param key   the cache key
     * @param fresh the token to cache
     */
    public void store(Key key, AccessToken fresh) {
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
        synchronized (entry) {
            entry.store(fresh, clock.instant());
        }
    }

    /**
     * Drops the cached token for a key, e.g. because the target rejected it.
     *
     * @param key the cache key
     */
    public void invalidate(Key key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            entry.clear();
        }
    }

    /** Waits for another caller's in-flight exchange, translating a failure into this caller's. */
    private AccessToken awaitCoalesced(Key key, CompletableFuture<AccessToken> exchange, Duration maxWait) {
        try {
            return exchange.get(maxWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TokenAcquisitionException(TokenAcquisitionException.Reason.VERIFIER_UNREACHABLE,
                    "Interrupted while waiting for the token exchange for %s.".formatted(key.value()), interrupted);
        } catch (TimeoutException timeout) {
            throw new TokenAcquisitionException(TokenAcquisitionException.Reason.VERIFIER_UNREACHABLE,
                    "The token exchange for %s did not finish within %s.".formatted(key.value(), maxWait), timeout);
        } catch (ExecutionException executionFailure) {
            Throwable cause = executionFailure.getCause() == null ? executionFailure : executionFailure.getCause();
            if (cause instanceof TokenAcquisitionException tokenAcquisitionException) {
                throw tokenAcquisitionException;
            }
            throw new CompletionException(cause);
        }
    }

    /**
     * Identifies one cached token. The wire form is opaque; use the factory methods, whose value
     * equality is what makes a changed target (a different service, client id or scope set) miss the
     * cache instead of reusing a token that does not speak for it.
     *
     * @param value the canonical key
     */
    public record Key(String value) {

        private static final String AUDIENCE_PREFIX = "audience:";
        private static final String SERVICE_PREFIX = "service:";
        private static final String FIELD_SEPARATOR = "|";
        private static final String SCOPE_SEPARATOR = " ";

        /**
         * Key for a configured token target, addressed by its audience name.
         *
         * @param audience the configured audience
         * @return the cache key
         */
        public static Key forAudience(String audience) {
            return new Key(AUDIENCE_PREFIX + audience);
        }

        /**
         * Key for an outbound call to a target service. Scopes are sorted, so the key does not depend
         * on the iteration order of the configured set.
         *
         * @param serviceUri the target service ({@code scheme://host[:port]})
         * @param clientId   the OID4VP {@code client_id} presented to it
         * @param scopes     the OID4VP scopes requested for it
         * @return the cache key
         */
        public static Key forService(URI serviceUri, String clientId, Set<String> scopes) {
            return new Key(SERVICE_PREFIX + serviceUri + FIELD_SEPARATOR + clientId + FIELD_SEPARATOR
                    + scopes.stream().sorted().collect(Collectors.joining(SCOPE_SEPARATOR)));
        }
    }

    /** Cache slot for one key; guarded by its own monitor. */
    private static final class Entry {

        private AccessToken token;

        /** When the cached token actually stops being accepted, as reported by the verifier. */
        private Instant expiresAt;

        /** When the cached token stops being handed out, {@link #REFRESH_SKEW} ahead of {@link #expiresAt}. */
        private Instant staleAt;

        /** Returns the cached token when it is still fresh at {@code now}, else {@code null}. */
        private AccessToken valid(Instant now) {
            if (token == null || staleAt == null || !now.isBefore(staleAt)) {
                return null;
            }
            long remaining = Math.max(0L, Duration.between(now, expiresAt).toSeconds());
            return new AccessToken(token.value(), token.tokenType(), remaining);
        }

        /** Caches {@code fresh}; a token without a usable lifetime is not cached at all. */
        private void store(AccessToken fresh, Instant now) {
            if (fresh == null || fresh.expiresInSeconds() <= 0) {
                clear();
                return;
            }
            Duration lifetime = Duration.ofSeconds(fresh.expiresInSeconds());
            Duration ttl = lifetime.compareTo(REFRESH_SKEW) > 0
                    ? lifetime.minus(REFRESH_SKEW)
                    : lifetime.dividedBy(SHORT_LIVED_TTL_DIVISOR);
            token = fresh;
            expiresAt = now.plus(lifetime);
            staleAt = now.plus(ttl);
        }

        private void clear() {
            token = null;
            expiresAt = null;
            staleAt = null;
        }
    }
}
