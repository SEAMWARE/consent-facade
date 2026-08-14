package org.fiware.consent.auth;

import io.github.wistefan.oid4vp.OID4VPClient;
import io.github.wistefan.oid4vp.config.RequestParameters;
import io.github.wistefan.oid4vp.exception.BadGatewayException;
import io.github.wistefan.oid4vp.model.TokenResponse;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link AuthHandler} that authenticates outbound requests over OID4VP, reactively on a {@code 401}:
 * it runs the request, and if the provider answers {@code 401 Unauthorized} it obtains an access
 * token by presenting the configured verifiable credential (deriving the authorization endpoint from
 * the request host via OIDC discovery), attaches it as a bearer token and retries once.
 *
 * <p>Only present when {@link Oid4VpConfiguration#isEnabled() OID4VP is enabled}. The OID4VP
 * {@code client_id} and {@code scope} are read from request attributes ({@link #CLIENT_ID_ATTRIBUTE} /
 * {@link #SCOPE_ATTRIBUTE}) the caller sets from the target provider's configuration.
 *
 * <p>Adapted from
 * <a href="https://github.com/FIWARE/contract-management">FIWARE/contract-management</a> (Apache-2.0).
 */
@Requires(condition = Oid4VpConfiguration.Oid4VpCondition.class)
@Slf4j
@Singleton
@RequiredArgsConstructor
public class Oid4VpAuthHandler implements AuthHandler {

    /** Request attribute carrying the OID4VP {@code client_id} for the call. */
    public static final String CLIENT_ID_ATTRIBUTE = "clientId";
    /** Request attribute carrying the OID4VP {@code scope} set for the call. */
    public static final String SCOPE_ATTRIBUTE = "scope";

    private final OID4VPClient oid4VPClient;

    @Override
    public Mono<HttpResponse> executeWithAuth(MutableHttpRequest<?> request,
                                              Function<MutableHttpRequest<?>, Mono<HttpResponse>> executor) {
        return executor.apply(request)
                .onErrorResume(throwable -> {
                    if (throwable instanceof HttpClientResponseException responseException) {
                        return Mono.just(responseException.getResponse());
                    }
                    throw new BadGatewayException("Was not able to call downstream service.", throwable);
                })
                .flatMap(response -> {
                    if (response.getStatus() != HttpStatus.UNAUTHORIZED) {
                        return Mono.just(response);
                    }
                    RequestParameters params = new RequestParameters(
                            serviceUri(request),
                            request.getPath(),
                            clientId(request),
                            scope(request));
                    return Mono.fromFuture(oid4VPClient.getAccessToken(params))
                            .map(TokenResponse::getAccessToken)
                            .flatMap(token -> {
                                request.bearerAuth(token);
                                return executor.apply(request);
                            });
                });
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
