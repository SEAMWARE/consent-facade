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
package org.fiware.consent.provider.persistence;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;

/**
 * The persisted form of a provider registration (plan §11.8): one row of the {@code provider} table.
 *
 * <p>Mirrors {@link org.fiware.consent.provider.ProviderConfig}; the database-backed
 * {@code PersistentProviderRegistry} maps between the two. The key column is named
 * {@code provider_key} because {@code key} is a reserved word in most SQL dialects.
 *
 * @param key             the provider key (primary key)
 * @param tmforumBaseUrl  base url of the provider's TM Forum backend
 * @param selfDescription this provider participant's own self-description URL (nullable)
 * @param clientId        this provider's OID4VP {@code client_id} (nullable)
 * @param scopes          this provider's OID4VP scopes, space-delimited (nullable)
 */
@MappedEntity("provider")
public record ProviderEntity(
        @Id @MappedProperty("provider_key") String key,
        String tmforumBaseUrl,
        @Nullable String selfDescription,
        @Nullable String clientId,
        @Nullable String scopes) {
}
