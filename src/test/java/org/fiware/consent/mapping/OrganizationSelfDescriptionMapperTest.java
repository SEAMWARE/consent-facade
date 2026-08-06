package org.fiware.consent.mapping;

import org.fiware.consent.configuration.FacadeProperties;
import org.fiware.consent.model.SelfDescriptionVO;
import org.fiware.consent.tmforum.party.model.CharacteristicVO;
import org.fiware.consent.tmforum.party.model.ContactMediumVO;
import org.fiware.consent.tmforum.party.model.MediumCharacteristicVO;
import org.fiware.consent.tmforum.party.model.OrganizationChildRelationshipVO;
import org.fiware.consent.tmforum.party.model.OrganizationIdentificationVO;
import org.fiware.consent.tmforum.party.model.OrganizationRefVO;
import org.fiware.consent.tmforum.party.model.OrganizationVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link OrganizationSelfDescriptionMapper}: the projection of a TM Forum organization
 * into the participant self-description the consent-manager's receipt builder consumes.
 */
class OrganizationSelfDescriptionMapperTest {

    private static final String DID_CHARACTERISTIC = "did";

    private final OrganizationSelfDescriptionMapper mapper = new OrganizationSelfDescriptionMapper(new FacadeProperties());

    private static OrganizationVO fullOrganization() {
        return new OrganizationVO()
                .name("ACME Data Ltd")
                .tradingName("ACME")
                .partyCharacteristic(List.of(new CharacteristicVO().name(DID_CHARACTERISTIC).value("did:web:acme")))
                .contactMedium(List.of(new ContactMediumVO().mediumType("PostalAddress")
                        .characteristic(new MediumCharacteristicVO().country("DE"))))
                .organizationIdentification(List.of(new OrganizationIdentificationVO().identificationId("HRB-12345")))
                .organizationChildRelationship(List.of(
                        new OrganizationChildRelationshipVO().organization(new OrganizationRefVO().id("child-1")),
                        new OrganizationChildRelationshipVO().organization(new OrganizationRefVO().id("child-2"))));
    }

    @Test
    void toSelfDescription_mapsAllReceiptFields() {
        SelfDescriptionVO selfDescription = mapper.toSelfDescription(fullOrganization());

        assertEquals("did:web:acme", selfDescription.getDid(), "The did comes from the configured party characteristic.");
        assertEquals("ACME Data Ltd", selfDescription.getLegalName(), "The legal name comes from the organization name.");
        assertEquals("HRB-12345", selfDescription.getLegalPerson().getRegistrationNumber(),
                "The registration number comes from the organization identification.");
        assertEquals("DE", selfDescription.getLegalPerson().getLegalAddress().getCountryCode(),
                "The legal address country code comes from the contact medium country.");
        assertEquals(List.of("child-1", "child-2"), selfDescription.getLegalPerson().getSubOrganization(),
                "The sub-organizations come from the child relationships.");
    }

    @Test
    void toSelfDescription_fallsBackToTradingNameForLegalName() {
        SelfDescriptionVO selfDescription = mapper.toSelfDescription(new OrganizationVO().tradingName("ACME"));

        assertEquals("ACME", selfDescription.getLegalName(), "The trading name is used when no name is present.");
    }

    @Test
    void toSelfDescription_alwaysProvidesLegalAddressAndSubOrganizations() {
        SelfDescriptionVO selfDescription = mapper.toSelfDescription(new OrganizationVO().name("Bare Org"));

        assertNotNull(selfDescription.getLegalPerson(), "A legal person is always present.");
        assertNotNull(selfDescription.getLegalPerson().getLegalAddress(),
                "A legal address is always present so the receipt builder never sees it missing.");
        assertNull(selfDescription.getLegalPerson().getLegalAddress().getCountryCode(),
                "Without a contact medium the country code is simply absent.");
        assertNotNull(selfDescription.getLegalPerson().getSubOrganization(), "The sub-organization list is never null.");
        assertTrue(selfDescription.getLegalPerson().getSubOrganization().isEmpty(),
                "An organization without children has an empty sub-organization list.");
    }

    @Test
    void toSelfDescription_returnsNullForNullOrganization() {
        assertNull(mapper.toSelfDescription(null), "A null organization maps to a null self-description.");
    }
}
