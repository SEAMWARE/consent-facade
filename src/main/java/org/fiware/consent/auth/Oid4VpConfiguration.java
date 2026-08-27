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

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import lombok.Data;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Configuration for authenticating the facade's outbound TM Forum requests with verifiable
 * credentials over OID4VP (implementation-plan.md). Off by default; when {@link #enabled}, the
 * {@link Oid4VpBeanFactory OID4VP client} and the {@link Oid4VpAuthHandler auth handler} are wired
 * and outbound calls are authenticated. When disabled, none of the auth beans exist and requests go
 * out unauthenticated.
 *
 * <p>Adapted, with modifications, from
 * <a href="https://github.com/FIWARE/contract-management">FIWARE/contract-management</a> (Apache-2.0);
 * see {@code NOTICE}.
 */
@Data
@Introspected
@ConfigurationProperties("oid4vp")
public class Oid4VpConfiguration {

    /** Default {@link #connectTimeout}. */
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** Default {@link #requestTimeout}. */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** Whether outbound TM Forum requests are authenticated over OID4VP. */
    private boolean enabled = false;
    /** The holder (the facade's) identity used to present the credential. */
    private Holder holder;
    /** Optional forward-proxy host for the OID4VP flow (used for local testing); {@code null} ⇒ no proxy. */
    @Nullable
    private String proxyHost;
    /** Optional forward-proxy port (required when {@link #proxyHost} is set). */
    @Nullable
    private Integer proxyPort;
    /** Filesystem folder the credentials to present are read from (JWT / SD-JWT). */
    private String credentialsFolder;
    /** Whether to check credential revocation. */
    private boolean enableRevocation = false;
    /**
     * How long the facade waits for the TCP connection to a verifier. Without it an unreachable-but-
     * accepting host can hold the connect attempt open indefinitely.
     */
    private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    /**
     * How long the facade waits for a whole OID4VP exchange to complete. A verifier that accepts the
     * connection and then stalls is a common failure mode; bounding the exchange is what stops it from
     * pinning the calling thread (and, on {@code POST /internal/tokens}, the blocking pool).
     */
    private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
    /** Trust anchors added on top of the system truststore (empty ⇒ system anchors only). */
    private List<String> trustAnchors = List.of();
    /**
     * Default OID4VP {@code client_id} presented to the provider's authorization endpoint. Used for a
     * provider that does not carry its own (see {@code ProviderConfig#clientId()}).
     */
    private String clientId = "";
    /**
     * Default OID4VP scopes requested for TM Forum access. Used for a provider that does not carry its
     * own (see {@code ProviderConfig#scopes()}).
     */
    private List<String> scopes = List.of();
    /**
     * The audiences the facade may obtain access tokens for via {@code POST /internal/tokens}.
     *
     * <p>Deliberately a closed list of <em>named</em> targets rather than a caller-supplied URL: a
     * caller that could name an arbitrary host would make the facade present the participant's
     * credential - a verifiable presentation naming it as holder - to that host on request. See
     * ADR-0003 (doc/adr/0003-token-endpoint-not-consent-proxy.md).
     */
    private List<TokenTarget> tokenTargets = List.of();

    /**
     * The holder identity. {@link #holderId} is optional: when absent, a {@code did:key} is derived
     * from the private key ({@link DidKeyGenerator}).
     *
     * @param holderId           the holder DID/URI, or {@code null} to derive a {@code did:key}
     * @param keyType            the key type (e.g. {@code EC})
     * @param keyPath            filesystem path to the PEM private key
     * @param signatureAlgorithm the signing algorithm (e.g. {@code ECDH-ES})
     */
    @ConfigurationProperties("holder")
    @Introspected
    public record Holder(@Nullable URI holderId, String keyType, String keyPath, String signatureAlgorithm) {
    }

    /**
     * A target the facade may obtain an OID4VP access token for, addressed by {@link #audience}.
     *
     * @param audience      the name callers use to ask for a token for this target; must be unique
     * @param url           the verifier/service base URL OIDC discovery runs against
     * @param clientId      the OID4VP {@code client_id} to present, or {@code null} for none
     * @param scope         the scopes to request; {@code null} or empty ⇒ the verifier's default scope
     * @param discoveryPath sub-path the target serves OIDC discovery under, inserted before
     *                      {@code /.well-known/openid-configuration}. Empty or {@code null} for the
     *                      spec location at the host root; a FIWARE VCVerifier serves it per service
     *                      (e.g. {@code /services/consent-manager}), and pointing at the root there
     *                      yields a 404 the client cannot parse.
     */
    @Introspected
    public record TokenTarget(String audience, URI url, @Nullable String clientId,
                              @Nullable List<String> scope, @Nullable String discoveryPath) {
    }

    /** Condition matching when OID4VP authentication is {@link Oid4VpConfiguration#enabled}. */
    public static class Oid4VpCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context) {
            return context.getBean(Oid4VpConfiguration.class).isEnabled();
        }
    }
}
