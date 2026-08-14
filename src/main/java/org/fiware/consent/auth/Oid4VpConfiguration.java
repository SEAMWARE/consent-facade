package org.fiware.consent.auth;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import lombok.Data;

import java.net.URI;
import java.util.List;

/**
 * Configuration for authenticating the facade's outbound TM Forum requests with verifiable
 * credentials over OID4VP (implementation-plan.md). Off by default; when {@link #enabled}, the
 * {@link Oid4VpBeanFactory OID4VP client} and the {@link Oid4VpAuthHandler auth handler} are wired
 * and outbound calls are authenticated. When disabled, none of the auth beans exist and requests go
 * out unauthenticated.
 *
 * <p>Adapted from the reference implementation
 * <a href="https://github.com/FIWARE/contract-management">FIWARE/contract-management</a> (Apache-2.0).
 */
@Data
@Introspected
@ConfigurationProperties("oid4vp")
public class Oid4VpConfiguration {

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
    /** Trust anchors added on top of the system truststore (empty ⇒ system anchors only). */
    private List<String> trustAnchors = List.of();
    /**
     * Default OID4VP {@code client_id} presented to the provider's authorization endpoint. A
     * per-provider override is planned via the admin API (implementation-plan.md, step 4).
     */
    private String clientId = "";
    /** Default OID4VP scopes requested for TM Forum access; per-provider override planned. */
    private List<String> scopes = List.of();

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

    /** Condition matching when OID4VP authentication is {@link Oid4VpConfiguration#enabled}. */
    public static class Oid4VpCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context) {
            return context.getBean(Oid4VpConfiguration.class).isEnabled();
        }
    }
}
