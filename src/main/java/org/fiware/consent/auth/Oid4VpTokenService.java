package org.fiware.consent.auth;

import io.github.wistefan.oid4vp.OID4VPClient;
import io.github.wistefan.oid4vp.config.RequestParameters;
import io.github.wistefan.oid4vp.exception.AuthorizationException;
import io.github.wistefan.oid4vp.exception.AuthorizationRequestException;
import io.github.wistefan.oid4vp.exception.BadGatewayException;
import io.github.wistefan.oid4vp.exception.ClientResolutionException;
import io.github.wistefan.oid4vp.exception.CredentialsAccessException;
import io.github.wistefan.oid4vp.model.TokenResponse;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.fiware.consent.auth.Oid4VpConfiguration.TokenTarget;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Obtains OID4VP access tokens for the {@link Oid4VpConfiguration#getTokenTargets() configured
 * audiences}, so that components which must not implement OID4VP themselves - notably the Go
 * consent-plugin - can authenticate as this participant.
 *
 * <p>Tokens are cached per audience and refreshed shortly before they expire, and concurrent misses
 * for the same audience are coalesced onto a single presentation: a burst of requests costs one
 * exchange with the verifier, not one per request.
 *
 * <p>See ADR-0002 (reuse this client rather than implementing OID4VP in Go) and ADR-0003 (expose it
 * as a token endpoint rather than proxying consent traffic) in {@code doc/adr/}.
 */
@Singleton
@Requires(condition = Oid4VpConfiguration.Oid4VpCondition.class)
@Slf4j
public class Oid4VpTokenService {

    /**
     * How long before actual expiry a cached token is considered stale. Covers the clock skew
     * between facade and verifier plus the time the caller still needs the token to be valid for.
     */
    private static final Duration REFRESH_SKEW = Duration.ofSeconds(60);

    /**
     * Divisor applied when a token lives no longer than {@link #REFRESH_SKEW}: such a token cannot
     * be cached for {@code expiresIn - skew} (that is not positive), so half its lifetime is used.
     */
    private static final int SHORT_LIVED_TTL_DIVISOR = 2;

    /** The token type to report when the verifier does not state one. */
    private static final String DEFAULT_TOKEN_TYPE = "Bearer";

    private final OID4VPClient oid4VPClient;
    private final Map<String, TokenTarget> targetsByAudience;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param oid4VPClient  the OID4VP client performing the presentation
     * @param configuration the OID4VP configuration carrying the permitted token targets
     */
    public Oid4VpTokenService(OID4VPClient oid4VPClient, Oid4VpConfiguration configuration) {
        this(oid4VPClient, configuration, Clock.systemUTC());
    }

    /**
     * Creates the service with an explicit clock.
     *
     * @param oid4VPClient  the OID4VP client performing the presentation
     * @param configuration the OID4VP configuration carrying the permitted token targets
     * @param clock         the clock used for cache expiry
     */
    Oid4VpTokenService(OID4VPClient oid4VPClient, Oid4VpConfiguration configuration, Clock clock) {
        this.oid4VPClient = oid4VPClient;
        this.clock = clock;
        Map<String, TokenTarget> targets = new HashMap<>();
        Optional.ofNullable(configuration.getTokenTargets()).orElse(List.of())
                .forEach(target -> targets.put(target.audience(), target));
        this.targetsByAudience = Map.copyOf(targets);
        log.info("OID4VP token service configured for audiences: {}.", this.targetsByAudience.keySet());
    }

    /**
     * Returns an access token for the given audience, from the cache when one is still valid.
     *
     * @param audience the configured audience to obtain a token for
     * @return the access token
     * @throws UnknownAudienceException    if no target is configured for {@code audience}
     * @throws TokenAcquisitionException if the token could not be obtained
     */
    public AccessToken tokenFor(String audience) {
        TokenTarget target = targetsByAudience.get(audience);
        if (target == null) {
            throw new UnknownAudienceException(audience);
        }
        // Get-or-create the entry (brief), then hold only this audience's lock for the exchange, so a
        // refresh for one audience never blocks hits or refreshes for another.
        CacheEntry entry = cache.computeIfAbsent(audience, key -> new CacheEntry());
        synchronized (entry) {
            AccessToken cached = entry.valid(clock.instant());
            if (cached != null) {
                return cached;
            }
            AccessToken fresh = request(target);
            entry.store(fresh, clock.instant());
            return fresh;
        }
    }

    /** Performs the actual OID4VP exchange, translating library failures into {@link TokenAcquisitionException.Reason}s. */
    private AccessToken request(TokenTarget target) {
        // The discovery path is per target: a VCVerifier serves OIDC discovery under
        // /services/{service}, and asking at the host root returns a 404 the client tries to
        // parse as the configuration document.
        RequestParameters parameters = new RequestParameters(
                target.url(), discoveryPathOf(target), target.clientId(), scopeOf(target));
        try {
            TokenResponse response = oid4VPClient.getAccessToken(parameters).join();
            if (response == null || response.getAccessToken() == null) {
                throw new TokenAcquisitionException(TokenAcquisitionException.Reason.VERIFIER_UNREACHABLE,
                        "The verifier for audience '%s' returned no access token.".formatted(target.audience()), null);
            }
            return new AccessToken(response.getAccessToken(),
                    Optional.ofNullable(response.getTokenType()).orElse(DEFAULT_TOKEN_TYPE),
                    response.getExpiresIn());
        } catch (CompletionException completionException) {
            throw translate(target, completionException.getCause() == null
                    ? completionException : completionException.getCause());
        } catch (TokenAcquisitionException tokenAcquisitionException) {
            throw tokenAcquisitionException;
        } catch (RuntimeException runtimeException) {
            throw translate(target, runtimeException);
        }
    }

    /**
     * Maps a library failure onto a {@link TokenAcquisitionException.Reason}. The distinction that
     * matters to callers is retryable (the verifier was not reachable) versus terminal (it refused
     * the credential, or this facade is misconfigured).
     */
    private static TokenAcquisitionException translate(TokenTarget target, Throwable cause) {
        String message = "Could not obtain an access token for audience '%s'.".formatted(target.audience());
        if (cause instanceof BadGatewayException) {
            return new TokenAcquisitionException(
                    TokenAcquisitionException.Reason.VERIFIER_UNREACHABLE, message, cause);
        }
        if (cause instanceof AuthorizationException
                || cause instanceof AuthorizationRequestException
                || cause instanceof ClientResolutionException) {
            return new TokenAcquisitionException(
                    TokenAcquisitionException.Reason.CREDENTIAL_REJECTED, message, cause);
        }
        if (cause instanceof CredentialsAccessException) {
            return new TokenAcquisitionException(
                    TokenAcquisitionException.Reason.MISCONFIGURED, message, cause);
        }
        return new TokenAcquisitionException(TokenAcquisitionException.Reason.MISCONFIGURED, message, cause);
    }

    private static String discoveryPathOf(TokenTarget target) {
        return target.discoveryPath() == null ? "" : target.discoveryPath();
    }

    private static Set<String> scopeOf(TokenTarget target) {
        return target.scope() == null ? Set.of() : new LinkedHashSet<>(target.scope());
    }

    /**
     * An access token for one audience.
     *
     * @param value            the token to put in the {@code Authorization} header
     * @param tokenType        the OAuth2 token type, normally {@code Bearer}
     * @param expiresInSeconds the token's remaining lifetime as reported by the verifier
     */
    public record AccessToken(String value, String tokenType, long expiresInSeconds) {
    }

    /** Cache slot for one audience; guarded by its own monitor. */
    private static final class CacheEntry {

        private AccessToken token;
        private Instant staleAt;

        /** Returns the cached token when it is still fresh at {@code now}, else {@code null}. */
        private AccessToken valid(Instant now) {
            if (token == null || staleAt == null || !now.isBefore(staleAt)) {
                return null;
            }
            long remaining = Duration.between(now, staleAt).toSeconds() + REFRESH_SKEW.toSeconds();
            return new AccessToken(token.value(), token.tokenType(), remaining);
        }

        /** Caches {@code fresh}; a token without a usable lifetime is not cached at all. */
        private void store(AccessToken fresh, Instant now) {
            if (fresh.expiresInSeconds() <= 0) {
                token = null;
                staleAt = null;
                return;
            }
            Duration lifetime = Duration.ofSeconds(fresh.expiresInSeconds());
            Duration ttl = lifetime.compareTo(REFRESH_SKEW) > 0
                    ? lifetime.minus(REFRESH_SKEW)
                    : lifetime.dividedBy(SHORT_LIVED_TTL_DIVISOR);
            token = fresh;
            staleAt = now.plus(ttl);
        }
    }
}
