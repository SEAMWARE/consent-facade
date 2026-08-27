package org.fiware.consent.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.nimbusds.jose.JWEAlgorithm;
import io.github.wistefan.dcql.DCQLEvaluator;
import io.github.wistefan.dcql.DcSdJwtCredentialEvaluator;
import io.github.wistefan.dcql.JwtCredentialEvaluator;
import io.github.wistefan.dcql.VcSdJwtCredentialEvaluator;
import io.github.wistefan.dcql.model.CredentialFormat;
import io.github.wistefan.dcql.model.TrustedAuthorityType;
import io.github.wistefan.oid4vp.HolderSigningService;
import io.github.wistefan.oid4vp.OID4VPClient;
import io.github.wistefan.oid4vp.SigningService;
import io.github.wistefan.oid4vp.client.X509SanDnsClientResolver;
import io.github.wistefan.oid4vp.config.HolderConfiguration;
import io.github.wistefan.oid4vp.credentials.CredentialsRepository;
import io.github.wistefan.oid4vp.credentials.FileSystemCredentialsRepository;
import io.github.wistefan.oid4vp.mapping.CredentialFormatDeserializer;
import io.github.wistefan.oid4vp.mapping.TrustedAuthorityTypeDeserializer;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.TrustAnchor;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wires the {@link OID4VPClient} used to authenticate the facade's outbound TM Forum calls. All beans
 * are gated on {@link Oid4VpConfiguration#isEnabled() OID4VP being enabled}, so nothing is built (and
 * no key/credential is loaded) in the default, unauthenticated deployment.
 *
 * <p>Adapted, with modifications, from
 * <a href="https://github.com/FIWARE/contract-management">FIWARE/contract-management</a> (Apache-2.0);
 * see {@code NOTICE} and {@code LICENSE-Apache-2.0}.
 */
@Factory
@Slf4j
@Requires(condition = Oid4VpConfiguration.Oid4VpCondition.class)
public class Oid4VpBeanFactory {

    private final CertReader certReader;

    /**
     * @param certReader reads the holder's PEM key and any additional trust anchors
     */
    public Oid4VpBeanFactory(CertReader certReader) {
        this.certReader = certReader;
    }

    /**
     * Low-level HTTP client (with optional proxy) the OID4VP flow uses. The connect timeout bounds the
     * one failure mode a request timeout cannot cover on its own: a host that never completes the
     * handshake.
     *
     * @param configuration the OID4VP configuration carrying the proxy and the connect timeout
     * @return the HTTP client
     */
    @Singleton
    public HttpClient oid4vpHttpClient(Oid4VpConfiguration configuration) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(configuration.getConnectTimeout());
        if (configuration.getProxyHost() != null && configuration.getProxyPort() != null) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(configuration.getProxyHost(), configuration.getProxyPort())));
        }
        return builder.build();
    }

    /**
     * The credentials the facade presents, read from {@code oid4vp.credentials-folder}.
     *
     * @param configuration the OID4VP configuration carrying the credentials folder
     * @param objectMapper  the mapper used to read them
     * @return the credentials repository
     */
    @Bean
    public CredentialsRepository credentialsRepository(Oid4VpConfiguration configuration, ObjectMapper objectMapper) {
        return new FileSystemCredentialsRepository(configuration.getCredentialsFolder(), objectMapper);
    }

    /**
     * The OID4VP client presenting the holder's credential for an access token.
     *
     * @param httpClient            the low-level client the flow runs over
     * @param objectMapper          the base mapper (copied and re-configured for the OID4VP wire format)
     * @param configuration         the OID4VP configuration
     * @param credentialsRepository the credentials to present
     * @return the OID4VP client
     */
    @Bean
    public OID4VPClient oid4VPClient(HttpClient httpClient,
                                     ObjectMapper objectMapper,
                                     Oid4VpConfiguration configuration,
                                     CredentialsRepository credentialsRepository) {
        Security.addProvider(new BouncyCastleProvider());

        ObjectMapper authObjectMapper = objectMapper.copy();
        authObjectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        SimpleModule deserializerModule = new SimpleModule();
        deserializerModule.addDeserializer(CredentialFormat.class, new CredentialFormatDeserializer());
        deserializerModule.addDeserializer(TrustedAuthorityType.class, new TrustedAuthorityTypeDeserializer());
        authObjectMapper.registerModule(deserializerModule);

        PrivateKey privateKey = certReader.loadPrivateKey(configuration.getHolder().keyPath());
        URI holderId = resolveHolderId(configuration.getHolder(), privateKey);
        HolderConfiguration holderConfiguration = new HolderConfiguration(
                holderId,
                holderId.toString(),
                JWEAlgorithm.parse(configuration.getHolder().signatureAlgorithm()),
                privateKey);
        SigningService signingService = new HolderSigningService(holderConfiguration, objectMapper);

        Set<TrustAnchor> trustAnchors = configuration.getTrustAnchors().stream()
                .map(certReader::loadCertificates)
                .flatMap(List::stream)
                .map(certificate -> new TrustAnchor(certificate, null))
                .collect(Collectors.toSet());
        if (trustAnchors.isEmpty()) {
            try {
                log.info("No trust anchors provided, loading system trust anchors.");
                trustAnchors = X509SanDnsClientResolver.getTrustAnchors();
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to load system trust anchors and no additional trust anchors provided.", e);
            }
        }
        X509SanDnsClientResolver clientResolver =
                new X509SanDnsClientResolver(trustAnchors, configuration.isEnableRevocation());

        DCQLEvaluator dcqlEvaluator = new DCQLEvaluator(List.of(
                new JwtCredentialEvaluator(),
                new DcSdJwtCredentialEvaluator(),
                new VcSdJwtCredentialEvaluator()));

        return new OID4VPClient(
                httpClient,
                holderConfiguration,
                authObjectMapper,
                List.of(clientResolver),
                dcqlEvaluator,
                credentialsRepository,
                signingService);
    }

    /**
     * Resolves the holder id: the configured {@code holderId} when present, otherwise a
     * {@code did:key} derived from the private key.
     *
     * @param holder     the holder configuration
     * @param privateKey the holder's private key (for {@code did:key} derivation)
     * @return the holder id URI
     */
    URI resolveHolderId(Oid4VpConfiguration.Holder holder, PrivateKey privateKey) {
        if (holder.holderId() != null) {
            return holder.holderId();
        }
        URI generatedDidKey = DidKeyGenerator.generateDidKey(privateKey);
        log.info("No holderId configured, generated did:key from the holder public key: {}.", generatedDidKey);
        return generatedDidKey;
    }
}
