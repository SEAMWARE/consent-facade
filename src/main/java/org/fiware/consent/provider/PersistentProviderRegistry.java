package org.fiware.consent.provider;

import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.fiware.consent.provider.persistence.ProviderEntity;
import org.fiware.consent.provider.persistence.ProviderRepository;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link ProviderRegistry} backed by a database (plan §11.8): the drop-in replacement for
 * {@link StaticProviderRegistry} that lets providers be managed at runtime through the admin
 * {@code /providers} API ({@link ProviderAdminController}) instead of only through configuration.
 *
 * <p>Selected by {@link ProviderRegistry#PERSISTENT_PROPERTY} = {@code true}. On first start (empty
 * table) it seeds itself from the same {@code facade.providers.*} configuration the static registry
 * reads, so a deployment can move from config to database without losing its providers. A
 * {@link ProviderRegistry#DEFAULT_PROVIDER_KEY default} provider is still required.
 *
 * <h2>Cache and consistency</h2>
 *
 * <p>Reads are served from an in-memory snapshot, because {@link #all()} and {@link #byKey(String)}
 * are called from the reactive request path - reading through to JDBC there would block an event-loop
 * thread. The snapshot is refreshed on every write <em>made through this instance</em> and, every
 * {@link #REFRESH_INTERVAL_PROPERTY refresh interval}, from the table.
 *
 * <p>That periodic refresh is what makes the registry usable with more than one replica: an admin
 * write lands on one replica, and the others pick it up within one interval rather than never. Callers
 * must tolerate that window - a provider created a moment ago may still be unknown to a sibling
 * replica. Writes are not lost, only not yet visible.
 */
@Slf4j
@Singleton
@Requires(property = ProviderRegistry.PERSISTENT_PROPERTY, value = "true")
public class PersistentProviderRegistry implements ProviderRegistry {

    /**
     * How often a replica re-reads the provider table. This is the upper bound on how long an admin
     * write made on one replica stays invisible to the others.
     */
    public static final String REFRESH_INTERVAL_PROPERTY = "facade.provider-registry.refresh-interval";

    /** Default {@link #REFRESH_INTERVAL_PROPERTY}. */
    private static final String DEFAULT_REFRESH_INTERVAL = "30s";

    /** Scopes are stored as a single space-delimited column, matching the OAuth2 {@code scope} syntax. */
    private static final String SCOPE_SEPARATOR = " ";

    /** Splits a stored scope column on any run of whitespace. */
    private static final String SCOPE_SPLIT_PATTERN = "\\s+";

    private final ProviderRepository repository;
    private final List<ProviderConfiguration> seedConfigurations;
    private final TMForumClientFactory clientFactory;

    /**
     * The current snapshot: an immutable map replaced wholesale by {@link #reload()}, so readers never
     * see a half-refreshed registry and never take a lock. Insertion order is the table's, which keeps
     * the fan-out in the participant-scoped lookups reproducible.
     */
    private volatile Map<String, ProviderConfig> providers = Map.of();

    /**
     * @param repository         the provider table
     * @param seedConfigurations the {@code facade.providers.*} entries used to seed an empty table
     * @param clientFactory      the factory whose cached per-provider clients must be dropped when a
     *                           provider's backend changes
     */
    public PersistentProviderRegistry(ProviderRepository repository,
                                      List<ProviderConfiguration> seedConfigurations,
                                      TMForumClientFactory clientFactory) {
        this.repository = repository;
        this.seedConfigurations = seedConfigurations;
        this.clientFactory = clientFactory;
    }

    /**
     * Seeds an empty table from configuration, loads the snapshot, and enforces the default-provider
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
        if (!providers.containsKey(DEFAULT_PROVIDER_KEY)) {
            throw new IllegalStateException(
                    "No default provider is registered. Seed 'facade.providers." + DEFAULT_PROVIDER_KEY
                            + "' or create it via 'POST /providers'.");
        }
    }

    @Override
    public Optional<ProviderConfig> byKey(String key) {
        return Optional.ofNullable(providers.get(key));
    }

    @Override
    public Collection<ProviderConfig> all() {
        return List.copyOf(providers.values());
    }

    /**
     * Whether a provider is registered under the given key.
     *
     * @param key the provider key
     * @return {@code true} if a provider is registered under {@code key}
     */
    public boolean exists(String key) {
        return providers.containsKey(key);
    }

    /**
     * Creates or updates a provider, then refreshes the snapshot.
     *
     * @param provider the provider to persist
     * @return the persisted provider
     */
    public ProviderConfig save(ProviderConfig provider) {
        ProviderEntity entity = toEntity(provider);
        // Each repository call runs in its own transaction, so the refresh below observes committed
        // state. Refreshing inside a transaction would leave the snapshot holding uncommitted rows if
        // it rolled back.
        if (repository.existsById(provider.key())) {
            repository.update(entity);
        } else {
            repository.save(entity);
        }
        reload();
        return providers.get(provider.key());
    }

    /**
     * Removes a provider, then refreshes the snapshot.
     *
     * @param key the provider key
     * @return {@code true} if a provider was removed, {@code false} if none was registered under {@code key}
     */
    public boolean delete(String key) {
        if (!repository.existsById(key)) {
            return false;
        }
        repository.deleteById(key);
        reload();
        return true;
    }

    /**
     * Re-reads the provider table, so writes made on another replica become visible here.
     *
     * <p>A failure is logged and swallowed: the previous snapshot stays in place, which serves requests
     * from a slightly stale registry rather than failing them because a scheduled refresh could not
     * reach the database.
     */
    @Scheduled(fixedDelay = "${" + REFRESH_INTERVAL_PROPERTY + ":" + DEFAULT_REFRESH_INTERVAL + "}")
    void refresh() {
        try {
            reload();
        } catch (RuntimeException refreshFailure) {
            log.warn("Could not refresh the provider registry; serving the previous snapshot.", refreshFailure);
        }
    }

    /**
     * Replaces the snapshot from the table, and drops the cached clients of every provider whose
     * resolved configuration changed - otherwise a {@code PUT /providers/{key}} that moves a provider
     * onto a new TM Forum backend would report success while requests kept going to the old one.
     */
    private void reload() {
        Map<String, ProviderConfig> refreshed = new LinkedHashMap<>();
        repository.findAll().forEach(entity -> refreshed.put(entity.key(), toConfig(entity)));
        Map<String, ProviderConfig> previous = providers;
        providers = Collections.unmodifiableMap(refreshed);
        previous.forEach((key, provider) -> {
            if (!provider.equals(refreshed.get(key))) {
                clientFactory.evict(key);
            }
        });
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

    /**
     * Joins scopes into the single space-delimited column. OAuth2 forbids whitespace inside a scope
     * token (RFC 6749 §3.3), so this round-trips for every legal scope; a scope carrying whitespace
     * would come back split and is rejected by {@link ProviderAdminController}.
     */
    private static String joinScopes(List<String> scopes) {
        return (scopes == null || scopes.isEmpty()) ? null : String.join(SCOPE_SEPARATOR, scopes);
    }

    private static List<String> splitScopes(String scopes) {
        return (scopes == null || scopes.isBlank()) ? null : List.of(scopes.trim().split(SCOPE_SPLIT_PATTERN));
    }
}
