package org.fiware.consent.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import lombok.Data;

/**
 * Configuration of the facade itself: its public base url, the self-description
 * identifiers of the provider/consumer parties and the TM Forum party
 * characteristic carrying the participant did.
 */
@Data
@ConfigurationProperties("facade")
public class FacadeProperties {

    /**
     * Public base url of this facade. Used to build the self-description urls the
     * generated contracts point back at.
     */
    private String selfUrl;

    private Party provider = new Party();

    private Party consumer = new Party();

    private PartyMapping party = new PartyMapping();

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
}
