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
