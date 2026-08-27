package org.fiware.consent.provider;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link ProviderRegistry} backed by static configuration ({@code facade.providers.*}, bound as
 * {@link ProviderConfiguration} beans).
 *
 * <p>This is the config-only implementation of the multi-provider design
 * ({@code REQUIREMENTS.md} §11.3): providers and their TM Forum endpoints are declared in
 * configuration and fixed for the lifetime of the process. {@link PersistentProviderRegistry}
 * (§11.8) is the alternative, selected by {@link ProviderRegistry#PERSISTENT_PROPERTY}, which reads
 * them from a database and lets the admin API change them at runtime.
 *
 * <p>A {@link ProviderRegistry#DEFAULT_PROVIDER_KEY default} provider is required, so an un-prefixed
 * id always resolves; its absence is a configuration error and fails fast at startup.
 *
 * <p>Active unless the {@link ProviderRegistry#PERSISTENT_PROPERTY persistent} registry is selected,
 * so the two never both claim the {@link ProviderRegistry} bean.
 */
@Slf4j
@Singleton
@Requires(property = ProviderRegistry.PERSISTENT_PROPERTY, notEquals = "true")
public class StaticProviderRegistry implements ProviderRegistry {

    private final Map<String, ProviderConfig> providersByKey;

    /**
     * Builds the registry from the configured provider entries.
     *
     * @param providerConfigurations the {@code facade.providers.*} entries (one per provider key)
     * @throws IllegalStateException if no {@link ProviderRegistry#DEFAULT_PROVIDER_KEY default}
     *                               provider is configured
     */
    public StaticProviderRegistry(List<ProviderConfiguration> providerConfigurations) {
        Map<String, ProviderConfig> providers = new LinkedHashMap<>();
        for (ProviderConfiguration configuration : providerConfigurations) {
            providers.put(configuration.getKey(), new ProviderConfig(
                    configuration.getKey(), configuration.getTmforumBaseUrl(), configuration.getSelfDescription(),
                    configuration.getClientId(), configuration.getScopes()));
        }
        if (!providers.containsKey(DEFAULT_PROVIDER_KEY)) {
            throw new IllegalStateException(
                    "No default provider is configured. Configure at least 'facade.providers."
                            + DEFAULT_PROVIDER_KEY + "' with its TM Forum base url.");
        }
        // an unmodifiable LinkedHashMap rather than Map.copyOf: all() is fanned out over by the
        // participant-scoped lookups, so a stable, configured order keeps their results reproducible
        this.providersByKey = Collections.unmodifiableMap(providers);
        log.info("Provider registry initialized with {} provider(s): {}.",
                providersByKey.size(), providersByKey.keySet());
    }

    @Override
    public Optional<ProviderConfig> byKey(String key) {
        return Optional.ofNullable(providersByKey.get(key));
    }

    @Override
    public Collection<ProviderConfig> all() {
        return providersByKey.values();
    }
}
