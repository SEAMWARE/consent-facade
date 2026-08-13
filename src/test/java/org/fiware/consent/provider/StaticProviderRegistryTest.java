package org.fiware.consent.provider;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link StaticProviderRegistry}: resolution of provider keys and the default-provider
 * invariant of the multi-provider plan ({@code REQUIREMENTS.md} §11.3).
 */
class StaticProviderRegistryTest {

    private static final String DEFAULT_URL = "http://tm-forum-api.default.svc:8080";
    private static final String PROVIDER_A_KEY = "provider-a";
    private static final String PROVIDER_A_URL = "http://tm-forum-api.provider-a.svc:8080";

    private static ProviderConfiguration configuration(String key, String tmforumBaseUrl) {
        ProviderConfiguration configuration = new ProviderConfiguration(key);
        configuration.setTmforumBaseUrl(tmforumBaseUrl);
        return configuration;
    }

    private static StaticProviderRegistry registryWith(ProviderConfiguration... configurations) {
        return new StaticProviderRegistry(List.of(configurations));
    }

    @Test
    void byKey_resolvesAConfiguredProvider() {
        StaticProviderRegistry registry = registryWith(
                configuration(ProviderRegistry.DEFAULT_PROVIDER_KEY, DEFAULT_URL),
                configuration(PROVIDER_A_KEY, PROVIDER_A_URL));

        Optional<ProviderConfig> resolved = registry.byKey(PROVIDER_A_KEY);

        assertTrue(resolved.isPresent(), "A configured provider key resolves to a provider.");
        assertEquals(PROVIDER_A_KEY, resolved.get().key(), "The resolved provider carries its key.");
        assertEquals(PROVIDER_A_URL, resolved.get().tmforumBaseUrl(),
                "The resolved provider carries its TM Forum base url.");
    }

    @Test
    void byKey_isEmptyForAnUnknownProvider() {
        StaticProviderRegistry registry = registryWith(
                configuration(ProviderRegistry.DEFAULT_PROVIDER_KEY, DEFAULT_URL));

        assertTrue(registry.byKey("does-not-exist").isEmpty(), "An unknown provider key resolves to empty.");
    }

    @Test
    void defaultProvider_returnsTheDefaultEntry() {
        StaticProviderRegistry registry = registryWith(
                configuration(ProviderRegistry.DEFAULT_PROVIDER_KEY, DEFAULT_URL),
                configuration(PROVIDER_A_KEY, PROVIDER_A_URL));

        ProviderConfig defaultProvider = registry.defaultProvider();

        assertEquals(ProviderRegistry.DEFAULT_PROVIDER_KEY, defaultProvider.key(),
                "The default provider is the one registered under the default key.");
        assertEquals(DEFAULT_URL, defaultProvider.tmforumBaseUrl(),
                "The default provider carries the default TM Forum base url.");
    }

    @Test
    void all_returnsEveryConfiguredProvider() {
        StaticProviderRegistry registry = registryWith(
                configuration(ProviderRegistry.DEFAULT_PROVIDER_KEY, DEFAULT_URL),
                configuration(PROVIDER_A_KEY, PROVIDER_A_URL));

        assertEquals(2, registry.all().size(), "Every configured provider is registered.");
    }

    @Test
    void construction_failsWhenNoDefaultProviderIsConfigured() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> registryWith(configuration(PROVIDER_A_KEY, PROVIDER_A_URL)),
                "A registry without a default provider is a configuration error.");
        assertTrue(failure.getMessage().contains(ProviderRegistry.DEFAULT_PROVIDER_KEY),
                "The failure names the missing default provider.");
    }

    @Test
    void construction_failsForAnEmptyConfiguration() {
        assertThrows(IllegalStateException.class, StaticProviderRegistryTest::registryWith,
                "A registry with no providers at all is a configuration error.");
    }
}
