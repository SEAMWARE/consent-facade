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

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Obtains OID4VP access tokens for the {@link Oid4VpConfiguration#getTokenTargets() configured
 * audiences}, so that components which must not implement OID4VP themselves - notably the Go
 * consent-plugin - can authenticate as this participant.
 *
 * <p>Tokens live in the shared {@link Oid4VpTokenCache}, which refreshes them shortly before they
 * expire and coalesces concurrent misses for the same audience onto a single presentation: a burst of
 * requests costs one exchange with the verifier, not one per request.
 *
 * <p>The exchange is bounded by {@link Oid4VpConfiguration#getRequestTimeout()}: a verifier that
 * accepts the connection and then stalls must fail the request rather than pin the calling thread.
 *
 * <p>See ADR-0002 (reuse this client rather than implementing OID4VP in Go) and ADR-0003 (expose it
 * as a token endpoint rather than proxying consent traffic) in {@code doc/adr/}.
 */
@Singleton
@Requires(condition = Oid4VpConfiguration.Oid4VpCondition.class)
@Slf4j
public class Oid4VpTokenService {

    private final OID4VPClient oid4VPClient;
    private final Map<String, TokenTarget> targetsByAudience;
    private final Oid4VpTokenCache tokenCache;
    private final Duration requestTimeout;

    /**
     * Creates the service.
     *
     * @param oid4VPClient  the OID4VP client performing the presentation
     * @param configuration the OID4VP configuration carrying the permitted token targets
     * @param tokenCache    the shared token cache
     */
    public Oid4VpTokenService(OID4VPClient oid4VPClient, Oid4VpConfiguration configuration,
                              Oid4VpTokenCache tokenCache) {
        this.oid4VPClient = oid4VPClient;
        this.tokenCache = tokenCache;
        this.requestTimeout = configuration.getRequestTimeout();
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
     * @throws UnknownAudienceException  if no target is configured for {@code audience}
     * @throws TokenAcquisitionException if the token could not be obtained
     */
    public AccessToken tokenFor(String audience) {
        TokenTarget target = targetsByAudience.get(audience);
        if (target == null) {
            throw new UnknownAudienceException(audience);
        }
        return tokenCache.getOrLoad(
                Oid4VpTokenCache.Key.forAudience(audience), requestTimeout, () -> request(target));
    }

    /** Performs the actual OID4VP exchange, translating library failures into {@link TokenAcquisitionException.Reason}s. */
    private AccessToken request(TokenTarget target) {
        // The discovery path is per target: a VCVerifier serves OIDC discovery under
        // /services/{service}, and asking at the host root returns a 404 the client tries to
        // parse as the configuration document.
        RequestParameters parameters = new RequestParameters(
                target.url(), discoveryPathOf(target), target.clientId(), scopeOf(target));
        try {
            TokenResponse response = oid4VPClient.getAccessToken(parameters)
                    .orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
            return AccessToken.from(response)
                    .orElseThrow(() -> new TokenAcquisitionException(
                            TokenAcquisitionException.Reason.VERIFIER_UNREACHABLE,
                            "The verifier for audience '%s' returned no access token.".formatted(target.audience()),
                            null));
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
     * matters to callers is retryable (the verifier was not reachable, or did not answer in time)
     * versus terminal (it refused the credential, or this facade is misconfigured).
     */
    private static TokenAcquisitionException translate(TokenTarget target, Throwable cause) {
        String message = "Could not obtain an access token for audience '%s'.".formatted(target.audience());
        if (cause instanceof BadGatewayException || cause instanceof TimeoutException) {
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
}
