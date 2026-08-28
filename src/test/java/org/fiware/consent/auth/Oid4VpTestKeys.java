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

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;

/** Test helper: generates throwaway EC key material for the OID4VP holder identity. */
final class Oid4VpTestKeys {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private Oid4VpTestKeys() {
    }

    /**
     * Generates an EC key pair via the BouncyCastle provider, so the PEM written by
     * {@link #writePrivateKeyPem} carries named-curve parameters BouncyCastle can read back (matching
     * a real openssl / did-helper key, unlike a plain JDK-provider key).
     */
    static KeyPair generateEcKeyPair(String curve) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        generator.initialize(new ECGenParameterSpec(curve));
        return generator.generateKeyPair();
    }

    /** Writes the given key pair's private key as a PEM file and returns the path. */
    static Path writePrivateKeyPem(KeyPair keyPair, Path path) throws Exception {
        StringWriter writer = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
            pemWriter.writeObject(keyPair.getPrivate());
        }
        Files.writeString(path, writer.toString());
        return path;
    }
}
