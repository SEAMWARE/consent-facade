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
package org.fiware.consent.configuration;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

/**
 * Keeps the <strong>internal</strong> API ({@code api/consent-facade-internal.yaml}) off the public
 * listener, and the public API off the internal one.
 *
 * <p>The internal endpoints are unauthenticated by design (ADR-0003): anything that reaches
 * {@code POST /internal/tokens} obtains a token that speaks for this participant, and anything that
 * reaches {@code /providers} rewrites the TM Forum backends the facade routes to. Until now the only
 * control was external - an ingress allow-list plus a NetworkPolicy, both living in another repository,
 * with nothing here that fails if they are misconfigured. A single ingress path added by someone who
 * has not read the internal spec turned a documented assumption into a participant-identity compromise.
 *
 * <p>This filter is that missing in-repo control: with two Netty listeners configured, a request is
 * served only if it arrived on the listener its path belongs to, and anything misdirected gets a
 * {@code 404} - the same answer as a path that does not exist, so the filter does not advertise the
 * internal API's existence to the public port.
 *
 * <p>Active only when {@link #INTERNAL_PORT_PROPERTY} names the internal listener's port; see
 * {@code application.yaml} for the listener configuration to pair it with. When it is unset,
 * {@link InternalApiExposureWarning} says so at startup.
 */
@ServerFilter(Filter.MATCH_ALL_PATTERN)
@Requires(property = InternalApiPortFilter.INTERNAL_PORT_PROPERTY)
@Slf4j
public class InternalApiPortFilter {

    /** Port of the listener the internal API is served on. Unset ⇒ no port isolation. */
    public static final String INTERNAL_PORT_PROPERTY = "facade.internal-port";

    /**
     * Path prefixes belonging to the internal API. These are the roots of the paths generated from
     * {@code api/consent-facade-internal.yaml}; extend this when that spec grows a new one.
     */
    private static final List<String> INTERNAL_PATH_PREFIXES = List.of("/internal", "/providers");

    /** The port value meaning "no listener": {@link #INTERNAL_PORT_PROPERTY} set to this disables the filter. */
    private static final int NO_PORT = -1;

    private final int internalPort;

    /**
     * @param internalPort the port the internal listener is bound to
     */
    public InternalApiPortFilter(@Value("${" + INTERNAL_PORT_PROPERTY + "}") int internalPort) {
        this.internalPort = internalPort;
        log.info("The internal API is restricted to the listener on port {}.", internalPort);
    }

    /**
     * Rejects a request that reached the wrong listener.
     *
     * @param request the inbound request
     * @return {@code 404} if the request's path does not belong to the listener it arrived on,
     *         otherwise {@code null} to continue
     */
    @RequestFilter
    @Nullable
    public HttpResponse<?> rejectMisdirectedRequest(HttpRequest<?> request) {
        if (internalPort == NO_PORT) {
            return null;
        }
        int port = listenerPortOf(request);
        if (port == NO_PORT) {
            // Cannot tell which listener this arrived on - fail open rather than break the deployment,
            // but say so, because the isolation is then not in effect.
            log.warn("Could not determine the listener port of {} {}; the internal-API port isolation "
                    + "is not being enforced for it.", request.getMethodName(), request.getPath());
            return null;
        }
        boolean internalPath = isInternalPath(request.getPath());
        boolean onInternalListener = port == internalPort;
        if (internalPath == onInternalListener) {
            return null;
        }
        log.warn("Refused {} {} on port {}: {}.", request.getMethodName(), request.getPath(), port,
                internalPath
                        ? "the internal API is only served on port " + internalPort
                        : "port " + internalPort + " serves the internal API only");
        return HttpResponse.notFound();
    }

    private static boolean isInternalPath(String path) {
        return INTERNAL_PATH_PREFIXES.stream().anyMatch(prefix ->
                path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    private static int listenerPortOf(HttpRequest<?> request) {
        SocketAddress address = request.getServerAddress();
        return address instanceof InetSocketAddress inetSocketAddress ? inetSocketAddress.getPort() : NO_PORT;
    }
}
