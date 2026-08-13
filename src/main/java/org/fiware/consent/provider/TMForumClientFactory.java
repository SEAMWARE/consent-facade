package org.fiware.consent.provider;

import io.micronaut.http.client.HttpClient;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.fiware.consent.tmforum.HttpTMForumApis;
import org.fiware.consent.tmforum.TMForumBackedRepository;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 *       for its base url; clients (per base url) and repositories (per provider key) are cached.</li>
 * </ul>
 *
 * <p>Until Phase 4 routes requests by provider, only the default branch is exercised at runtime; the
 * low-level branch is covered by tests and becomes live once a second provider is registered.
 */
@Slf4j
@Singleton
public class TMForumClientFactory {

    private final TMForumBackedRepository defaultRepository;
    private final Map<String, HttpClient> clientsByBaseUrl = new ConcurrentHashMap<>();
    private final Map<String, TMForumBackedRepository> repositoriesByProviderKey = new ConcurrentHashMap<>();

    /**
     * @param defaultRepository the context-managed repository over the generated clients, used for
     *                          the default provider
     */
    public TMForumClientFactory(TMForumBackedRepository defaultRepository) {
        this.defaultRepository = defaultRepository;
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
        return repositoriesByProviderKey.computeIfAbsent(provider.key(),
                key -> new TMForumBackedRepository(new HttpTMForumApis(clientFor(provider.tmforumBaseUrl()))));
    }

    private HttpClient clientFor(String baseUrl) {
        return clientsByBaseUrl.computeIfAbsent(baseUrl, TMForumClientFactory::createClient);
    }

    private static HttpClient createClient(String baseUrl) {
        try {
            return HttpClient.create(new URL(baseUrl));
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("A provider's TM Forum base url is not a valid URL: " + baseUrl, e);
        }
    }

    /** Closes the low-level clients created for non-default providers on shutdown. */
    @PreDestroy
    void close() {
        clientsByBaseUrl.values().forEach(HttpClient::close);
        clientsByBaseUrl.clear();
        repositoriesByProviderKey.clear();
    }
}
