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
package org.fiware.consent.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import lombok.Data;

/**
 * Configuration of the facade itself: its public base url, the provider party's self-description
 * identifier, and the TM Forum characteristics carrying the participant did and the processing
 * purpose.
 */
@Data
@ConfigurationProperties("facade")
public class FacadeProperties {

    /**
     * Public base url of this facade. Used to build the self-description urls the
     * generated contracts point back at.
     */
    private String selfUrl;

    /**
     * Self-description identifier of the provider participant, used as the {@code producedBy} of a
     * data resource when the resolved provider does not carry its own (see {@code REQUIREMENTS.md}
     * §11.7).
     */
    private Party provider = new Party();

    private PartyMapping party = new PartyMapping();

    private SpecMapping spec = new SpecMapping();

    @Data
    public static class Party {
        /** Self-description identifier of the party. */
        private String selfDescription;
    }

    @Data
    public static class PartyMapping {
        /** Name of the TM Forum party characteristic that carries the participant did. */
        private String didCharacteristic = "did";
    }

    @Data
    public static class SpecMapping {
        /**
         * Name of the TM Forum product-specification characteristic that carries the processing
         * purpose (see {@code REQUIREMENTS.md} §0.2). Its value's {@code name} becomes the
         * software-resource name the consent-manager records as the consent purpose.
         */
        private String purposeCharacteristic = "purpose";
    }
}
