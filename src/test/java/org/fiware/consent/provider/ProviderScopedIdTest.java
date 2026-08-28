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
package org.fiware.consent.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ProviderScopedId}: the {@code providerKey~localId} wire form the facade uses to
 * carry the owning provider inside every id it mints (multi-provider plan, {@code REQUIREMENTS.md}
 * §11.4).
 */
class ProviderScopedIdTest {

    @Test
    void encode_joinsKeyAndLocalIdWithTheSeparator() {
        assertEquals("provider-x~agreement-1", ProviderScopedId.of("provider-x", "agreement-1").encode(),
                "The wire form is providerKey~localId.");
    }

    @ParameterizedTest
    @CsvSource({
            "provider-x~agreement-1, provider-x, agreement-1",
            "default~urn:ngsi-ld:product-specification:1234, default, urn:ngsi-ld:product-specification:1234"
    })
    void decode_splitsOnTheFirstSeparatorSoLocalIdsMayContainColons(String encoded, String expectedKey, String expectedLocalId) {
        ProviderScopedId decoded = ProviderScopedId.decode(encoded);

        assertEquals(expectedKey, decoded.providerKey(), "The provider key is the part before the first separator.");
        assertEquals(expectedLocalId, decoded.localId(),
                "The local id is everything after the first separator - colons in urn ids are preserved.");
    }

    @Test
    void decode_treatsABareIdAsADefaultProviderLocalId() {
        ProviderScopedId decoded = ProviderScopedId.decode("urn:ngsi-ld:agreement:42");

        assertEquals(ProviderRegistry.DEFAULT_PROVIDER_KEY, decoded.providerKey(),
                "A bare id (no separator) belongs to the default provider - note it is not confused by urn colons.");
        assertEquals("urn:ngsi-ld:agreement:42", decoded.localId(), "The whole bare id is the local id.");
    }

    @Test
    void encodeThenDecode_roundTrips() {
        ProviderScopedId original = ProviderScopedId.of("provider-x", "urn:ngsi-ld:agreement:42");

        assertEquals(original, ProviderScopedId.decode(original.encode()), "encode then decode is the identity.");
    }

    @Test
    void constructor_rejectsAProviderKeyContainingTheSeparator() {
        assertThrows(IllegalArgumentException.class, () -> ProviderScopedId.of("bad~key", "local"),
                "A provider key must not contain the separator, else decoding would be ambiguous.");
    }
}
