package org.fiware.consent.auth;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * Executes an outbound request, adding authentication when required. The facade injects it as an
 * {@code Optional<AuthHandler>}: present ⇒ authenticated calls, absent (OID4VP disabled) ⇒ the
 * request is executed unchanged (unauthenticated).
 *
 * <p>Adapted from
 * <a href="https://github.com/FIWARE/contract-management">FIWARE/contract-management</a> (Apache-2.0).
 */
public interface AuthHandler {

    /**
     * Executes the given request through the executor, adding authentication as needed. When no auth
     * is required the request is executed without any other interaction.
     *
     * @param request  the outbound request (its {@code clientId}/{@code scope} attributes carry the
     *                 OID4VP parameters)
     * @param executor performs the actual exchange for a (possibly modified) request
     * @return the response
     */
    Mono<HttpResponse> executeWithAuth(MutableHttpRequest<?> request,
                                       Function<MutableHttpRequest<?>, Mono<HttpResponse>> executor);
}
