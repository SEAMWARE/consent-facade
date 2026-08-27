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

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.ClientFilterChain;
import io.micronaut.http.filter.HttpClientFilter;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Authenticates the default provider's outbound TM Forum calls (the generated declarative
 * {@code @Client(id=…)} beans). Scoped to the five TM Forum service ids, it stamps the OID4VP
 * {@code client_id}/{@code scope} onto the request and delegates to the {@link AuthHandler}, which
 * adds a bearer token reactively on a {@code 401} (implementation-plan.md, path a).
 *
 * <p>Only present when {@link Oid4VpConfiguration#isEnabled() OID4VP is enabled}; otherwise the
 * generated clients call the TM Forum API unfiltered (unauthenticated).
 */
@Requires(condition = Oid4VpConfiguration.Oid4VpCondition.class)
@Filter(serviceId = {"agreement", "party", "product-catalog", "product-inventory", "product-order"})
@RequiredArgsConstructor
public class TmForumAuthClientFilter implements HttpClientFilter {

    private final AuthHandler authHandler;
    private final Oid4VpConfiguration configuration;

    @Override
    public Publisher<? extends HttpResponse<?>> doFilter(MutableHttpRequest<?> request, ClientFilterChain chain) {
        request.setAttribute(Oid4VpAuthHandler.CLIENT_ID_ATTRIBUTE, configuration.getClientId());
        request.setAttribute(Oid4VpAuthHandler.SCOPE_ATTRIBUTE, Set.copyOf(configuration.getScopes()));
        return authHandler
                .executeWithAuth(request,
                        proceededRequest -> Mono.from(chain.proceed(proceededRequest)).map(response -> (HttpResponse) response))
                .map(response -> (HttpResponse<?>) response);
    }
}
