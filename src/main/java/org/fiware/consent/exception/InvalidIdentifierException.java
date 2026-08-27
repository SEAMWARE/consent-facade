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
package org.fiware.consent.exception;

/**
 * Thrown when an identifier arriving on a public path variable cannot be accepted, because using it
 * would let the caller shape the facade's outbound request rather than just name a resource.
 *
 * <p>Handled by {@link InvalidIdentifierExceptionHandler}, which answers {@code 400} with the reason.
 */
public class InvalidIdentifierException extends IllegalArgumentException {

    /**
     * @param message the reason the identifier was rejected; it reaches the caller, so it must not
     *                carry anything but the rule that was broken
     */
    public InvalidIdentifierException(String message) {
        super(message);
    }
}
