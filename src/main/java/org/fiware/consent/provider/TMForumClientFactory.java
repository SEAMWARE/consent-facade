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
package org.fiware.consent.provider;

import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.http.client.HttpClient;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.fiware.consent.auth.AuthHandler;
import org.fiware.consent.auth.Oid4VpConfiguration;
import org.fiware.consent.tmforum.HttpTMForumApis;
import org.fiware.consent.tmforum.TMForumBackedRepository;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Produces the {@link TMForumBackedRepository} that reads a given provider's TM Forum backend.
 *
 * <p>This is the crux of the multi-provider support ({@code REQUIREMENTS.md} §11.5): the generated
 * declarative TM Forum clients are bound to a compile-time service url and cannot be retargeted per
 * request, so a request for another provider is served by a repository over a low-level client bound
 * to <em>that</em> provider's base url.
 *
 * <ul>
 *   <li>The {@link ProviderRegistry#DEFAULT_PROVIDER_KEY default} provider keeps using the injected,
 *       context-managed repository (the generated clients) - unchanged behaviour.</li>
 *   <li>Every other provider gets a repository over an {@link HttpTMForumApis} on a client created
 *       for its base url.</li>
 * </ul>
 *
 * <h2>Caching</h2>
 *
 * <p>Repositories are cached on the whole resolved {@link ProviderConfig}, not on the provider key: a
 * key-only cache captured the base url, client id and scopes on first use, so a
 * {@code PUT /providers/{key}} that moved a provider onto a new TM Forum backend answered {@code 200}
 * while every subsequent request still went to the old one, for the lifetime of the process.
 * {@code ProviderConfig} is a record, so value equality does that for free. {@link #evict(String)}
 * additionally drops the superseded entries - and closes the clients they no longer reference - so a
 * long-lived process does not accumulate them.
 */
@Slf4j
@Singleton
public class TMForumClientFactory {

    /**
     * How long a low-level client waits for the TCP connection to a provider's backend. Without it,
     * {@code HttpClient.create} leaves the connect attempt unbounded.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** How long a low-level client waits for a provider's backend to answer; matches {@code application.yaml}. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final TMForumBackedRepository defaultRepository;
    private final Optional<AuthHandler> authHandler;
    private final Oid4VpConfiguration oid4VpConfiguration;
    private final Map<String, HttpClient> clientsByBaseUrl = new ConcurrentHashMap<>();
    private final Map<ProviderConfig, TMForumBackedRepository> repositoriesByProvider = new ConcurrentHashMap<>();

    /**
     * @param defaultRepository   the context-managed repository over the generated clients, used for
     *                            the default provider
     * @param authHandler         the OID4VP auth handler (empty when OID4VP is disabled) used to
     *                            authenticate non-default providers' low-level requests
     * @param oid4VpConfiguration supplies the default OID4VP {@code client_id}/scopes, used for a
     *                            provider that overrides neither
     */
    public TMForumClientFactory(TMForumBackedRepository defaultRepository,
                                Optional<AuthHandler> authHandler,
                                Oid4VpConfiguration oid4VpConfiguration) {
        this.defaultRepository = defaultRepository;
        this.authHandler = authHandler;
        this.oid4VpConfiguration = oid4VpConfiguration;
    }

    /**
     * Returns the repository that reads the given provider's TM Forum backend, creating (and caching)
     * a low-level one for a non-default provider.
     *
     * @param provider the provider to read from
     * @return the repository bound to that provider's backend
     */
    public TMForumBackedRepository forProvider(ProviderConfig provider) {
        if (ProviderRegistry.DEFAULT_PROVIDER_KEY.equals(provider.key())) {
            return defaultRepository;
        }
        return repositoriesByProvider.computeIfAbsent(provider,
                config -> new TMForumBackedRepository(new HttpTMForumApis(
                        clientFor(config.tmforumBaseUrl()),
                        authHandler,
                        resolveClientId(config),
                        resolveScopes(config))));
    }

    /**
     * Drops everything cached for a provider key, closing any client no longer referenced.
     *
     * <p>Called by the {@link PersistentProviderRegistry} when a provider is updated or removed, so a
     * superseded repository and its connection pool do not linger for the lifetime of the process.
     *
     * @param providerKey the key whose cached repositories are dropped
     */
    public void evict(String providerKey) {
        boolean evicted = repositoriesByProvider.keySet()
                .removeIf(provider -> provider.key().equals(providerKey));
        if (evicted) {
            log.info("Dropped the cached TM Forum client(s) of provider '{}'.", providerKey);
        }
        closeUnreferencedClients();
    }

    /** The provider's OID4VP {@code client_id}, falling back to the facade default. */
    String resolveClientId(ProviderConfig provider) {
        return (provider.clientId() != null && !provider.clientId().isBlank())
                ? provider.clientId()
                : oid4VpConfiguration.getClientId();
    }

    /** The provider's OID4VP scopes, falling back to the facade default. */
    Set<String> resolveScopes(ProviderConfig provider) {
        List<String> scopes = (provider.scopes() != null && !provider.scopes().isEmpty())
                ? provider.scopes()
                : oid4VpConfiguration.getScopes();
        return Set.copyOf(scopes);
    }

    private HttpClient clientFor(String baseUrl) {
        return clientsByBaseUrl.computeIfAbsent(baseUrl, TMForumClientFactory::createClient);
    }

    private static HttpClient createClient(String baseUrl) {
        DefaultHttpClientConfiguration configuration = new DefaultHttpClientConfiguration();
        configuration.setConnectTimeout(CONNECT_TIMEOUT);
        configuration.setReadTimeout(READ_TIMEOUT);
        try {
            return HttpClient.create(new URL(baseUrl), configuration);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("A provider's TM Forum base url is not a valid URL: " + baseUrl, e);
        }
    }

    /** Closes and forgets every client no longer used by a cached repository. */
    private void closeUnreferencedClients() {
        Set<String> baseUrlsInUse = repositoriesByProvider.keySet().stream()
                .map(ProviderConfig::tmforumBaseUrl)
                .collect(Collectors.toSet());
        Iterator<Map.Entry<String, HttpClient>> clients = clientsByBaseUrl.entrySet().iterator();
        while (clients.hasNext()) {
            Map.Entry<String, HttpClient> client = clients.next();
            if (!baseUrlsInUse.contains(client.getKey())) {
                closeQuietly(client.getKey(), client.getValue());
                clients.remove();
            }
        }
    }

    private static void closeQuietly(String baseUrl, HttpClient client) {
        try {
            client.close();
        } catch (RuntimeException closeFailure) {
            log.warn("Could not close the TM Forum client for {}.", baseUrl, closeFailure);
        }
    }

    /** Closes the low-level clients created for non-default providers on shutdown. */
    @PreDestroy
    void close() {
        clientsByBaseUrl.forEach(TMForumClientFactory::closeQuietly);
        clientsByBaseUrl.clear();
        repositoriesByProvider.clear();
    }
}
