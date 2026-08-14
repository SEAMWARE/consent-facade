package org.fiware.consent.provider;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.fiware.consent.provider.persistence.ProviderRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PersistentProviderRegistry}: seeding an empty table from configuration and
 * managing providers at runtime, backed by an in-memory H2 (plan §11.8). Each test uses a distinct
 * provider key so methods do not interfere.
 */
@MicronautTest(transactional = false)
@Property(name = ProviderRegistry.PERSISTENT_PROPERTY, value = "true")
@Property(name = "datasources.default.url",
        value = "jdbc:h2:mem:provider-registry;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@Property(name = "datasources.default.driver-class-name", value = "org.h2.Driver")
@Property(name = "datasources.default.username", value = "sa")
@Property(name = "datasources.default.dialect", value = "POSTGRES")
@Property(name = "flyway.datasources.default.enabled", value = "true")
class PersistentProviderRegistryTest {

    @Inject
    PersistentProviderRegistry registry;

    @Inject
    ProviderRepository repository;

    private static ProviderConfig provider(String key, String tmforumBaseUrl) {
        return new ProviderConfig(key, tmforumBaseUrl, null, null, null);
    }

    @Test
    void seedsTheDefaultProviderFromConfigurationOnFirstStart() {
        assertTrue(registry.byKey(ProviderRegistry.DEFAULT_PROVIDER_KEY).isPresent(),
                "The default provider from facade.providers.* is seeded into the empty table.");
        assertEquals(ProviderRegistry.DEFAULT_PROVIDER_KEY, registry.defaultProvider().key(),
                "The default provider is resolvable.");
        assertTrue(repository.existsById(ProviderRegistry.DEFAULT_PROVIDER_KEY),
                "The seeded default provider is persisted.");
    }

    @Test
    void save_persistsANewProviderAndReflectsItInTheCache() {
        registry.save(provider("reg-save", "http://tm-forum-api.reg-save.svc:8080"));

        assertTrue(repository.existsById("reg-save"), "The provider is persisted.");
        assertEquals("http://tm-forum-api.reg-save.svc:8080", registry.byKey("reg-save").orElseThrow().tmforumBaseUrl(),
                "The new provider is resolvable from the refreshed cache.");
    }

    @Test
    void save_updatesAnExistingProvider() {
        registry.save(provider("reg-update", "http://old:8080"));
        registry.save(provider("reg-update", "http://new:8080"));

        assertEquals("http://new:8080", registry.byKey("reg-update").orElseThrow().tmforumBaseUrl(),
                "Saving an existing key updates it rather than duplicating.");
    }

    @Test
    void delete_removesAProviderFromTheDatabaseAndTheCache() {
        registry.save(provider("reg-delete", "http://tm-forum-api.reg-delete.svc:8080"));

        assertTrue(registry.delete("reg-delete"), "Deleting a registered provider reports success.");
        assertTrue(registry.byKey("reg-delete").isEmpty(), "The deleted provider is gone from the cache.");
        assertFalse(repository.existsById("reg-delete"), "The deleted provider is gone from the database.");
    }

    @Test
    void delete_isFalseForAnUnknownProvider() {
        assertFalse(registry.delete("reg-absent"), "Deleting an unregistered provider reports no-op.");
    }

    @Test
    void all_containsTheDefaultAndEveryRegisteredProvider() {
        registry.save(provider("reg-all", "http://tm-forum-api.reg-all.svc:8080"));

        assertTrue(registry.all().stream().anyMatch(p -> p.key().equals(ProviderRegistry.DEFAULT_PROVIDER_KEY)),
                "all() includes the default provider.");
        assertTrue(registry.all().stream().anyMatch(p -> p.key().equals("reg-all")),
                "all() includes a registered provider.");
    }
}
