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
