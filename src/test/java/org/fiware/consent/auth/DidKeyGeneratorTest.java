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
package org.fiware.consent.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DidKeyGenerator}: deriving a {@code did:key} from an EC private key (used when the
 * holder id is not configured).
 */
class DidKeyGeneratorTest {

    @ParameterizedTest
    @ValueSource(strings = {"secp256r1", "secp384r1"})
    void generateDidKey_producesADeterministicDidKeyForSupportedCurves(String curve) throws Exception {
        KeyPair keyPair = Oid4VpTestKeys.generateEcKeyPair(curve);

        URI did = DidKeyGenerator.generateDidKey(keyPair.getPrivate());

        assertTrue(did.toString().startsWith("did:key:z"), "A did:key uses the base58btc (z) multibase prefix.");
        assertEquals(did, DidKeyGenerator.generateDidKey(keyPair.getPrivate()),
                "The did:key derivation is deterministic for the same key.");
    }

    @Test
    void generateDidKey_rejectsUnsupportedKeyTypes() throws Exception {
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);

        assertThrows(IllegalArgumentException.class,
                () -> DidKeyGenerator.generateDidKey(rsa.generateKeyPair().getPrivate()),
                "Only EC P-256/P-384 keys are supported.");
    }
}
