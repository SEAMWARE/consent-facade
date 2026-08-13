package org.fiware.consent.provider;

import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link ProviderRegistry} backed by static configuration ({@code facade.providers.*}, bound as
 * {@link ProviderConfiguration} beans).
 *
 * <p>This is the starting-point implementation of the multi-provider plan
 * ({@code REQUIREMENTS.md} §11.3): providers and their TM Forum endpoints are declared in
 * configuration. A later {@code PersistentProviderRegistry} (plan §11.8) replaces it with a
 * database-backed source and an admin API, without changing the {@link ProviderRegistry} interface
 * the rest of the facade depends on.
 *
 * <p>A {@link ProviderRegistry#DEFAULT_PROVIDER_KEY default} provider is required, so single-provider
 * deployments (and every deployment before provider-keyed routing is wired) always have a provider
 * to fall back to; its absence is a configuration error and fails fast at startup.
 */
@Slf4j
@Singleton
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
                    configuration.getKey(), configuration.getTmforumBaseUrl(), configuration.getSelfDescription()));
        }
        if (!providers.containsKey(DEFAULT_PROVIDER_KEY)) {
            throw new IllegalStateException(
                    "No default provider is configured. Configure at least 'facade.providers."
                            + DEFAULT_PROVIDER_KEY + "' with its TM Forum base url.");
        }
        this.providersByKey = Map.copyOf(providers);
        log.info("Provider registry initialized with {} provider(s): {}.",
                providersByKey.size(), providersByKey.keySet());
    }

    @Override
    public Optional<ProviderConfig> byKey(String key) {
        return Optional.ofNullable(providersByKey.get(key));
    }

    @Override
    public ProviderConfig defaultProvider() {
        return providersByKey.get(DEFAULT_PROVIDER_KEY);
    }

    @Override
    public Collection<ProviderConfig> all() {
        return providersByKey.values();
    }
}
