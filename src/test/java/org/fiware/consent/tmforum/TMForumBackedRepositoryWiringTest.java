package org.fiware.consent.tmforum;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that {@link TMForumBackedRepository} is injectable, i.e. that it wires up over the
 * generated TM Forum agreement- and party-catalog clients in the application context.
 */
@MicronautTest
class TMForumBackedRepositoryWiringTest {

    @Inject
    TMForumBackedRepository repository;

    @Test
    void repository_isWiredOverGeneratedClients() {
        assertNotNull(repository, "The repository should be injectable, backed by the generated TM Forum clients.");
    }
}
