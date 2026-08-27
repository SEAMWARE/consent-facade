package org.fiware.consent.auth;

import io.github.wistefan.oid4vp.OID4VPClient;
import io.github.wistefan.oid4vp.config.RequestParameters;
import io.github.wistefan.oid4vp.exception.BadGatewayException;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link AuthHandler} that authenticates outbound requests over OID4VP.
 *
 * <p>A token cached for the target service (see {@link Oid4VpTokenCache}) is attached up front, so the
 * steady state is a single authenticated request. Only when there is no cached token, or the target
 * answers {@code 401 Unauthorized}, does the handler present the configured verifiable credential
 * (deriving the authorization endpoint from the request host via OIDC discovery), cache the resulting
 * token and retry once. Without that cache every outbound TM Forum call - and the participant-scoped
 * lookups make one per registered provider - would cost an unauthorized round trip plus a full
 * verifiable presentation.
 *
 * <p>Only present when {@link Oid4VpConfiguration#isEnabled() OID4VP is enabled}. The OID4VP
 * {@code client_id} and {@code scope} are read from request attributes ({@link #CLIENT_ID_ATTRIBUTE} /
 * {@link #SCOPE_ATTRIBUTE}) the caller sets from the target provider's configuration; they are part of
 * the cache key, so a token is never reused for a target it does not speak for.
 *
 * <p>Adapted, with modifications, from
 * <a href="https://github.com/FIWARE/contract-management">FIWARE/contract-management</a> (Apache-2.0);
 * see {@code NOTICE} and {@code LICENSE-Apache-2.0}.
 */
@Requires(condition = Oid4VpConfiguration.Oid4VpCondition.class)
@Slf4j
@Singleton
public class Oid4VpAuthHandler implements AuthHandler {

    /** Request attribute carrying the OID4VP {@code client_id} for the call. */
    public static final String CLIENT_ID_ATTRIBUTE = "clientId";
    /** Request attribute carrying the OID4VP {@code scope} set for the call. */
    public static final String SCOPE_ATTRIBUTE = "scope";

    /**
     * Discovery lives at the service root ({@code /.well-known/openid-configuration}): the library
     * appends any non-empty path <em>before</em> {@code /.well-known}, which for the actual request
     * path (e.g. {@code /tmf-api/party/...}) would hit a protected sub-path and get the provider's
     * HTML 401 instead of the OIDC config.
     */
    private static final String DISCOVERY_AT_SERVICE_ROOT = "";

    private final OID4VPClient oid4VPClient;
    private final Oid4VpTokenCache tokenCache;
    private final Duration requestTimeout;

    /**
     * @param oid4VPClient  the OID4VP client performing the presentation
     * @param tokenCache    the shared token cache
     * @param configuration the OID4VP configuration carrying the exchange timeout
     */
    public Oid4VpAuthHandler(OID4VPClient oid4VPClient, Oid4VpTokenCache tokenCache,
                             Oid4VpConfiguration configuration) {
        this.oid4VPClient = oid4VPClient;
        this.tokenCache = tokenCache;
        this.requestTimeout = configuration.getRequestTimeout();
    }

    @Override
    public Mono<HttpResponse> executeWithAuth(MutableHttpRequest<?> request,
                                              Function<MutableHttpRequest<?>, Mono<HttpResponse>> executor) {
        Oid4VpTokenCache.Key cacheKey = cacheKeyFor(request);
        AccessToken cached = tokenCache.lookup(cacheKey);
        if (cached != null) {
            attachBearer(request, cached.value());
        }
        return execute(request, executor)
                .flatMap(response -> {
                    if (response.getStatus() != HttpStatus.UNAUTHORIZED) {
                        return Mono.just(response);
                    }
                    // Either nothing was cached, or the target refused what was: drop it and present
                    // the credential again.
                    tokenCache.invalidate(cacheKey);
                    return presentCredential(request, cacheKey)
                            .flatMap(token -> {
                                attachBearer(request, token);
                                return execute(request, executor);
                            });
                });
    }

    /**
     * Sets the bearer credential, replacing any already on the request. {@code bearerAuth} only
     * <em>appends</em>, so retrying a request that already carried a (refused) token would send two
     * {@code Authorization} headers and the target would keep seeing the stale one.
     */
    private static void attachBearer(MutableHttpRequest<?> request, String token) {
        request.getHeaders().remove(HttpHeaders.AUTHORIZATION);
        request.bearerAuth(token);
    }

    /** Runs the exchange, turning an error-status response back into a response the caller can inspect. */
    private static Mono<HttpResponse> execute(MutableHttpRequest<?> request,
                                              Function<MutableHttpRequest<?>, Mono<HttpResponse>> executor) {
        return executor.apply(request)
                .onErrorResume(throwable -> {
                    if (throwable instanceof HttpClientResponseException responseException) {
                        return Mono.just(responseException.getResponse());
                    }
                    return Mono.error(new BadGatewayException("Was not able to call downstream service.", throwable));
                });
    }

    /** Presents the credential for a fresh token, caching it for the next call to the same target. */
    private Mono<String> presentCredential(HttpRequest<?> request, Oid4VpTokenCache.Key cacheKey) {
        RequestParameters parameters = new RequestParameters(
                serviceUri(request), DISCOVERY_AT_SERVICE_ROOT, clientId(request), scope(request));
        return Mono.fromFuture(() -> oid4VPClient.getAccessToken(parameters))
                .timeout(requestTimeout)
                .flatMap(response -> Mono.justOrEmpty(AccessToken.from(response)))
                .doOnNext(token -> tokenCache.store(cacheKey, token))
                .map(AccessToken::value);
    }

    /** The cache key for this call: the target service plus the identity presented to it. */
    private static Oid4VpTokenCache.Key cacheKeyFor(HttpRequest<?> request) {
        return Oid4VpTokenCache.Key.forService(serviceUri(request), clientId(request), scope(request));
    }

    /** The target service URI ({@code scheme://host[:port]}); the library discovers OIDC on it. */
    private static URI serviceUri(HttpRequest<?> request) {
        int port = request.getUri().getPort();
        String portSuffix = port < 0 ? "" : ":" + port;
        return URI.create(request.getUri().getScheme() + "://" + request.getUri().getHost() + portSuffix);
    }

    private static String clientId(HttpRequest<?> request) {
        return request.getAttribute(CLIENT_ID_ATTRIBUTE)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .orElse("");
    }

    private static Set<String> scope(HttpRequest<?> request) {
        return request.getAttribute(SCOPE_ATTRIBUTE, Set.class)
                .map(set -> (Set<?>) set)
                .orElse(Set.of())
                .stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(Collectors.toSet());
    }
}
