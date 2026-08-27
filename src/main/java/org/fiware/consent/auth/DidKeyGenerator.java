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

import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.net.URI;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;

/**
 * Generates {@code did:key} DIDs from Java {@link PrivateKey} objects following the
 * <a href="https://w3c-ccg.github.io/did-key-spec/">W3C did:key specification</a>. Used when the
 * holder identity is not configured explicitly.
 *
 * <p>Supported: EC P-256 (secp256r1) and P-384 (secp384r1).
 *
 * <p>Adapted, with modifications, from
 * <a href="https://github.com/FIWARE/contract-management">FIWARE/contract-management</a> (Apache-2.0);
 * see {@code NOTICE}.
 */
public final class DidKeyGenerator {

    private static final String DID_KEY_PREFIX = "did:key:";
    private static final char MULTIBASE_BASE58BTC_PREFIX = 'z';
    private static final String CURVE_P256 = "secp256r1";
    private static final String CURVE_P384 = "secp384r1";

    /** Multicodec unsigned varint prefix for P-256 (secp256r1) public keys (code 0x1200). */
    static final byte[] MULTICODEC_P256_PREFIX = {(byte) 0x80, (byte) 0x24};
    /** Multicodec unsigned varint prefix for P-384 (secp384r1) public keys (code 0x1201). */
    static final byte[] MULTICODEC_P384_PREFIX = {(byte) 0x81, (byte) 0x24};

    private static final BigInteger P256_ORDER = ECNamedCurveTable.getParameterSpec(CURVE_P256).getN();
    private static final BigInteger P384_ORDER = ECNamedCurveTable.getParameterSpec(CURVE_P384).getN();

    private static final String UNSUPPORTED_KEY_TYPE_MESSAGE =
            "Unsupported key type: %s. Supported types: EC (P-256, P-384).";
    private static final String UNSUPPORTED_CURVE_MESSAGE =
            "Unsupported EC curve. Supported curves: secp256r1 (P-256), secp384r1 (P-384).";

    private DidKeyGenerator() {
    }

    /**
     * Generates a {@code did:key} URI from the given private key.
     *
     * @param privateKey the private key (EC P-256 or P-384)
     * @return the generated {@code did:key} URI
     * @throws IllegalArgumentException if the key type or curve is not supported
     */
    public static URI generateDidKey(PrivateKey privateKey) {
        if (privateKey instanceof ECPrivateKey ecPrivateKey) {
            return generateEcDidKey(ecPrivateKey);
        }
        throw new IllegalArgumentException(String.format(UNSUPPORTED_KEY_TYPE_MESSAGE, privateKey.getAlgorithm()));
    }

    private static URI generateEcDidKey(ECPrivateKey ecPrivateKey) {
        String curveName = identifyCurve(ecPrivateKey);
        byte[] multicodecPrefix = getMulticodecPrefix(curveName);
        byte[] compressedPublicKey = deriveCompressedPublicKey(ecPrivateKey, curveName);

        byte[] prefixedKey = new byte[multicodecPrefix.length + compressedPublicKey.length];
        System.arraycopy(multicodecPrefix, 0, prefixedKey, 0, multicodecPrefix.length);
        System.arraycopy(compressedPublicKey, 0, prefixedKey, multicodecPrefix.length, compressedPublicKey.length);

        String encoded = Base58.encode(prefixedKey);
        return URI.create(DID_KEY_PREFIX + MULTIBASE_BASE58BTC_PREFIX + encoded);
    }

    private static String identifyCurve(ECPrivateKey ecPrivateKey) {
        BigInteger order = ecPrivateKey.getParams().getOrder();
        if (P256_ORDER.equals(order)) {
            return CURVE_P256;
        } else if (P384_ORDER.equals(order)) {
            return CURVE_P384;
        }
        throw new IllegalArgumentException(UNSUPPORTED_CURVE_MESSAGE);
    }

    private static byte[] getMulticodecPrefix(String curveName) {
        return switch (curveName) {
            case CURVE_P256 -> MULTICODEC_P256_PREFIX;
            case CURVE_P384 -> MULTICODEC_P384_PREFIX;
            default -> throw new IllegalArgumentException(UNSUPPORTED_CURVE_MESSAGE);
        };
    }

    private static byte[] deriveCompressedPublicKey(ECPrivateKey ecPrivateKey, String curveName) {
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec(curveName);
        BigInteger d = ecPrivateKey.getS();
        ECPoint publicPoint = spec.getG().multiply(d).normalize();
        return publicPoint.getEncoded(true);
    }

    /**
     * Base58-BTC encoder (Bitcoin alphabet) for the multibase {@code z} prefix.
     */
    static final class Base58 {

        private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
        private static final BigInteger BASE = BigInteger.valueOf(58);

        private Base58() {
        }

        static String encode(byte[] input) {
            if (input.length == 0) {
                return "";
            }
            int leadingZeros = 0;
            while (leadingZeros < input.length && input[leadingZeros] == 0) {
                leadingZeros++;
            }
            BigInteger value = new BigInteger(1, input);
            StringBuilder sb = new StringBuilder();
            while (value.compareTo(BigInteger.ZERO) > 0) {
                BigInteger[] divmod = value.divideAndRemainder(BASE);
                value = divmod[0];
                sb.append(ALPHABET.charAt(divmod[1].intValue()));
            }
            for (int i = 0; i < leadingZeros; i++) {
                sb.append('1');
            }
            return sb.reverse().toString();
        }
    }
}
