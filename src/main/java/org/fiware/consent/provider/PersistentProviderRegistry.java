package org.fiware.consent.provider;

import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.fiware.consent.provider.persistence.ProviderEntity;
import org.fiware.consent.provider.persistence.ProviderRepository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ProviderRegistry} backed by a database (plan §11.8): the drop-in replacement for
 * {@link StaticProviderRegistry} that lets providers be managed at runtime through the admin
 * {@code /providers} API ({@link ProviderAdminController}) instead of only through configuration.
 *
 * <p>Selected by {@link ProviderRegistry#PERSISTENT_PROPERTY} = {@code true}. On first start (empty
 * table) it seeds itself from the same {@code facade.providers.*} configuration the static registry
 * reads, so a deployment can move from config to database without losing its providers. Reads are
 * served from an in-memory cache that is refreshed on every write; a {@link
 * ProviderRegistry#DEFAULT_PROVIDER_KEY default} provider is still required.
 */
@Slf4j
@Singleton
@Requires(property = ProviderRegistry.PERSISTENT_PROPERTY, value = "true")
public class PersistentProviderRegistry implements ProviderRegistry {

    private final ProviderRepository repository;
    private final List<ProviderConfiguration> seedConfigurations;
    private final Map<String, ProviderConfig> cache = new ConcurrentHashMap<>();

    /**
     * @param repository         the provider table
     * @param seedConfigurations the {@code facade.providers.*} entries used to seed an empty table
     */
    public PersistentProviderRegistry(ProviderRepository repository, List<ProviderConfiguration> seedConfigurations) {
        this.repository = repository;
        this.seedConfigurations = seedConfigurations;
    }

    /**
     * Seeds an empty table from configuration, loads the cache, and enforces the default-provider
     * invariant.
     *
     * @throws IllegalStateException if no default provider is present after seeding
     */
    @PostConstruct
    void initialize() {
        if (repository.count() == 0 && !seedConfigurations.isEmpty()) {
            repository.saveAll(seedConfigurations.stream().map(PersistentProviderRegistry::toEntity).toList());
            log.info("Seeded the persistent provider registry from configuration: {}.",
                    seedConfigurations.stream().map(ProviderConfiguration::getKey).toList());
        }
        reload();
        if (!cache.containsKey(DEFAULT_PROVIDER_KEY)) {
            throw new IllegalStateException(
                    "No default provider is registered. Seed 'facade.providers." + DEFAULT_PROVIDER_KEY
                            + "' or create it via 'POST /providers'.");
        }
    }

    @Override
    public Optional<ProviderConfig> byKey(String key) {
        return Optional.ofNullable(cache.get(key));
    }

    @Override
    public ProviderConfig defaultProvider() {
        return cache.get(DEFAULT_PROVIDER_KEY);
    }

    @Override
    public Collection<ProviderConfig> all() {
        return List.copyOf(cache.values());
    }

    /**
     * Whether a provider is registered under the given key.
     *
     * @param key the provider key
     * @return {@code true} if a provider is registered under {@code key}
     */
    public boolean exists(String key) {
        return cache.containsKey(key);
    }

    /**
     * Creates or updates a provider and refreshes the cache.
     *
     * @param provider the provider to persist
     * @return the persisted provider
     */
    @Transactional
    public ProviderConfig save(ProviderConfig provider) {
        ProviderEntity entity = toEntity(provider);
        if (repository.existsById(provider.key())) {
            repository.update(entity);
        } else {
            repository.save(entity);
        }
        reload();
        return cache.get(provider.key());
    }

    /**
     * Removes a provider and refreshes the cache.
     *
     * @param key the provider key
     * @return {@code true} if a provider was removed, {@code false} if none was registered under {@code key}
     */
    @Transactional
    public boolean delete(String key) {
        if (!repository.existsById(key)) {
            return false;
        }
        repository.deleteById(key);
        reload();
        return true;
    }

    private void reload() {
        Map<String, ProviderConfig> refreshed = new LinkedHashMap<>();
        repository.findAll().forEach(entity -> refreshed.put(entity.key(), toConfig(entity)));
        cache.keySet().retainAll(refreshed.keySet());
        cache.putAll(refreshed);
    }

    private static ProviderEntity toEntity(ProviderConfiguration configuration) {
        return new ProviderEntity(configuration.getKey(), configuration.getTmforumBaseUrl(),
                configuration.getSelfDescription(), configuration.getClientId(), joinScopes(configuration.getScopes()));
    }

    private static ProviderEntity toEntity(ProviderConfig provider) {
        return new ProviderEntity(provider.key(), provider.tmforumBaseUrl(), provider.selfDescription(),
                provider.clientId(), joinScopes(provider.scopes()));
    }

    private static ProviderConfig toConfig(ProviderEntity entity) {
        return new ProviderConfig(entity.key(), entity.tmforumBaseUrl(), entity.selfDescription(),
                entity.clientId(), splitScopes(entity.scopes()));
    }

    /** Scopes are stored as a single space-delimited column; {@code null}/empty ⇒ no override. */
    private static String joinScopes(List<String> scopes) {
        return (scopes == null || scopes.isEmpty()) ? null : String.join(" ", scopes);
    }

    private static List<String> splitScopes(String scopes) {
        return (scopes == null || scopes.isBlank()) ? null : List.of(scopes.trim().split("\\s+"));
    }
}
