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

import lombok.Getter;

/**
 * Thrown when an OID4VP access token could not be obtained for a known audience.
 *
 * <p>Carries a {@link Reason} so callers can distinguish a transient failure from a permanent one.
 * The consent-plugin fails closed on a missing token, so "retry shortly" and "this will never work"
 * must not look alike: the former is a blip, the latter needs an operator.
 */
@Getter
public class TokenAcquisitionException extends RuntimeException {

    /** Why the token could not be obtained. */
    public enum Reason {
        /** The verifier could not be reached, or did not speak the expected protocol. Retryable. */
        VERIFIER_UNREACHABLE,
        /** The verifier refused the presented credential (unknown issuer, expired, not in the TIR). */
        CREDENTIAL_REJECTED,
        /** The facade's own OID4VP setup is broken (holder key, credential files). Not retryable. */
        MISCONFIGURED
    }

    private final Reason reason;

    /**
     * Creates the exception.
     *
     * @param reason  why the token could not be obtained
     * @param message what went wrong
     * @param cause   the underlying failure, or {@code null}
     */
    public TokenAcquisitionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }
}
