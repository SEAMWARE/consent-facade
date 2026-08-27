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

/**
 * Thrown when a token is requested for an audience that is not configured in
 * {@link Oid4VpConfiguration#getTokenTargets()}.
 *
 * <p>This is a caller error, not a server fault: the set of audiences the facade will present the
 * participant's credential to is closed by configuration (ADR-0003).
 */
public class UnknownAudienceException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param audience the audience that is not configured
     */
    public UnknownAudienceException(String audience) {
        super("No token target is configured for audience '%s'.".formatted(audience));
    }
}
