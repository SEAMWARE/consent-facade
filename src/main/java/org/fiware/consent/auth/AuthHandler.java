/*
 * Copyright 2026 Seamless Middleware Technologies S.L and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
 * <p>Adapted, with modifications, from
 * <a href="https://github.com/FIWARE/contract-management">FIWARE/contract-management</a> (Apache-2.0);
 * see {@code NOTICE}.
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
