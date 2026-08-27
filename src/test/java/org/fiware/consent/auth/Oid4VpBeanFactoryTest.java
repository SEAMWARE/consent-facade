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

import io.github.wistefan.oid4vp.OID4VPClient;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Oid4VpBeanFactory}: that enabling OID4VP wires the {@link OID4VPClient} and the
 * {@link AuthHandler} from a holder key + credentials folder, and that the holder id is resolved
 * from config or derived as a {@code did:key}.
 */
class Oid4VpBeanFactoryTest {

    private static Map<String, Object> enabledConfig(Path keyPath, Path credentialsFolder) {
        return Map.of(
                "oid4vp.enabled", true,
                "oid4vp.holder.key-path", keyPath.toString(),
                "oid4vp.holder.key-type", "EC",
                "oid4vp.holder.signature-algorithm", "ECDH-ES",
                "oid4vp.credentials-folder", credentialsFolder.toString());
    }

    @Test
    void enabled_wiresTheOid4vpClientAndAuthHandler() throws Exception {
        KeyPair keyPair = Oid4VpTestKeys.generateEcKeyPair("secp256r1");
        Path keyPath = Oid4VpTestKeys.writePrivateKeyPem(keyPair, Files.createTempFile("holder", ".pem"));
        Path credentialsFolder = Files.createTempDirectory("credentials");

        try (ApplicationContext context = ApplicationContext.run(enabledConfig(keyPath, credentialsFolder))) {
            assertNotNull(context.getBean(OID4VPClient.class), "The OID4VP client is wired when enabled.");
            assertNotNull(context.getBean(AuthHandler.class), "The auth handler is wired when enabled.");
        }
    }

    @Test
    void resolveHolderId_prefersTheConfiguredIdOverAGeneratedDidKey() throws Exception {
        Oid4VpBeanFactory factory = new Oid4VpBeanFactory(new CertReader());
        KeyPair keyPair = Oid4VpTestKeys.generateEcKeyPair("secp256r1");

        URI configured = URI.create("did:web:facade.example");
        assertEquals(configured,
                factory.resolveHolderId(new Oid4VpConfiguration.Holder(configured, "EC", "n/a", "ECDH-ES"), keyPair.getPrivate()),
                "A configured holderId is used as-is.");

        URI derived = factory.resolveHolderId(
                new Oid4VpConfiguration.Holder(null, "EC", "n/a", "ECDH-ES"), keyPair.getPrivate());
        assertTrue(derived.toString().startsWith("did:key:z"), "Without a configured id, a did:key is derived.");
    }
}
