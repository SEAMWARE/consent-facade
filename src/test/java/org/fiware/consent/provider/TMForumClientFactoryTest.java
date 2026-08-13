package org.fiware.consent.provider;

import org.fiware.consent.tmforum.TMForumBackedRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link TMForumClientFactory}: routing the default provider to the injected repository
 * and building (and caching) a low-level repository per non-default provider (multi-provider plan,
 * {@code REQUIREMENTS.md} §11.5).
 */
class TMForumClientFactoryTest {

    private static final ProviderConfig DEFAULT_PROVIDER =
            new ProviderConfig(ProviderRegistry.DEFAULT_PROVIDER_KEY, "http://tm-forum-api.default.svc:8080");
    private static final ProviderConfig PROVIDER_A =
            new ProviderConfig("provider-a", "http://tm-forum-api.provider-a.svc:8080");
    private static final ProviderConfig PROVIDER_B =
            new ProviderConfig("provider-b", "http://tm-forum-api.provider-b.svc:8080");

    private TMForumBackedRepository defaultRepository;
    private TMForumClientFactory factory;

    @BeforeEach
    void setUp() {
        defaultRepository = mock(TMForumBackedRepository.class);
        factory = new TMForumClientFactory(defaultRepository);
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    @Test
    void forProvider_returnsTheInjectedRepositoryForTheDefaultProvider() {
        assertSame(defaultRepository, factory.forProvider(DEFAULT_PROVIDER),
                "The default provider is served by the context-managed repository over the generated clients.");
    }

    @Test
    void forProvider_buildsALowLevelRepositoryForANonDefaultProvider() {
        TMForumBackedRepository repository = factory.forProvider(PROVIDER_A);

        assertNotNull(repository, "A non-default provider gets its own repository.");
        assertNotSame(defaultRepository, repository, "It is not the default repository.");
    }

    @Test
    void forProvider_cachesTheRepositoryPerProviderKey() {
        assertSame(factory.forProvider(PROVIDER_A), factory.forProvider(PROVIDER_A),
                "The same provider resolves to the same (cached) repository.");
    }

    @Test
    void forProvider_buildsADistinctRepositoryPerProvider() {
        assertNotSame(factory.forProvider(PROVIDER_A), factory.forProvider(PROVIDER_B),
                "Different providers get different repositories.");
    }
}
