package org.fiware.consent.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fiware.consent.configuration.FacadeProperties;
import org.fiware.consent.model.DataResourceVO;
import org.fiware.consent.model.ServiceOfferingVO;
import org.fiware.consent.model.SoftwareResourceVO;
import org.fiware.consent.tmforum.productcatalog.model.CharacteristicValueSpecificationVO;
import org.fiware.consent.tmforum.productcatalog.model.ProductSpecificationCharacteristicVO;
import org.fiware.consent.tmforum.productcatalog.model.ProductSpecificationVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CatalogMapper}: bundling an agreement's specifications into one service offering
 * (both data and software resources), mapping a specification into a data resource, and reading the
 * purpose characteristic into a software resource - all with URLs the facade also serves.
 */
class CatalogMapperTest {

    private static final String SELF_URL = "http://facade.example";
    private static final String PROVIDER_SD = "did:web:provider.example";
    private static final String PURPOSE_CHARACTERISTIC = "purpose";

    private final FacadeProperties facadeProperties = facadeProperties();
    private final CatalogMapper mapper =
            new CatalogMapper(new CatalogUrls(facadeProperties), facadeProperties, new ObjectMapper());

    private static FacadeProperties facadeProperties() {
        FacadeProperties facadeProperties = new FacadeProperties();
        facadeProperties.setSelfUrl(SELF_URL);
        facadeProperties.getProvider().setSelfDescription(PROVIDER_SD);
        return facadeProperties;
    }

    @Test
    void toServiceOffering_bundlesSpecificationsAsDataAndSoftwareResources() {
        ServiceOfferingVO offering = mapper.toServiceOffering("agr-1", List.of("spec-1", "spec-2"));

        assertEquals(SELF_URL + "/catalog/serviceofferings/agr-1", offering.getAtId(),
                "The offering @id is this facade's service-offering URL for the agreement.");
        assertEquals("ServiceOffering", offering.getAtType(), "The offering carries its @type.");
        assertEquals(
                List.of(SELF_URL + "/catalog/dataresources/spec-1", SELF_URL + "/catalog/dataresources/spec-2"),
                offering.getDataResources(),
                "Every specification becomes one data-resource URL (the data).");
        assertEquals(
                List.of(SELF_URL + "/catalog/softwareresources/spec-1", SELF_URL + "/catalog/softwareresources/spec-2"),
                offering.getSoftwareResources(),
                "Every specification becomes one software-resource URL (the purpose).");
        assertEquals(Boolean.TRUE, offering.getUserInteraction(), "Granting consent requires user interaction.");
    }

    @Test
    void toServiceOffering_withoutSpecificationsHasEmptyResources() {
        ServiceOfferingVO offering = mapper.toServiceOffering("agr-1", List.of());

        assertTrue(offering.getDataResources().isEmpty(), "An agreement without specifications bundles no data resources.");
        assertTrue(offering.getSoftwareResources().isEmpty(), "An agreement without specifications bundles no software resources.");
    }

    @Test
    void toDataResource_mapsSpecificationWithRequiredFields() {
        ProductSpecificationVO specification = new ProductSpecificationVO()
                .id("spec-1").name("Customer profile").description("The customer's profile data");

        DataResourceVO dataResource = mapper.toDataResource(specification);

        assertEquals(SELF_URL + "/catalog/dataresources/spec-1", dataResource.getAtId(), "The data-resource @id is its facade URL.");
        assertEquals("DataResource", dataResource.getAtType(), "The data resource carries its @type.");
        assertEquals("Customer profile", dataResource.getName(), "The name comes from the specification.");
        assertEquals("The customer's profile data", dataResource.getDescription(), "The description comes from the specification.");
        assertEquals(PROVIDER_SD, dataResource.getProducedBy(), "producedBy is the provider self-description.");
        assertEquals(Boolean.TRUE, dataResource.getContainsPII(), "Consent-gated data always contains PII.");
    }

    @Test
    void toDataResource_returnsNullForNullSpecification() {
        assertNull(mapper.toDataResource(null), "A null specification maps to a null data resource.");
    }

    @Test
    void toSoftwareResource_readsPurposeNameFromObjectCharacteristic() {
        ProductSpecificationVO specification = specificationWithPurpose(
                Map.of("id", "svc-provision", "name", "Service provision", "description", "Deliver the service"));

        SoftwareResourceVO softwareResource = mapper.toSoftwareResource(specification);

        assertEquals(SELF_URL + "/catalog/softwareresources/spec-1", softwareResource.getAtId(), "The software-resource @id is its facade URL.");
        assertEquals("SoftwareResource", softwareResource.getAtType(), "The software resource carries its @type.");
        assertEquals("Service provision", softwareResource.getName(), "The name is the purpose name - the consent purpose.");
        assertEquals("Deliver the service", softwareResource.getDescription(), "The description comes from the purpose.");
    }

    @Test
    void toSoftwareResource_acceptsAPlainStringPurposeValue() {
        ProductSpecificationVO specification = specificationWithPurpose("Service provision");

        assertEquals("Service provision", mapper.toSoftwareResource(specification).getName(),
                "A plain-string purpose characteristic value is taken as the purpose name.");
    }

    @Test
    void toSoftwareResource_fallsBackToSpecificationNameWhenNoPurpose() {
        ProductSpecificationVO specification = new ProductSpecificationVO().id("spec-1").name("Customer profile");

        assertEquals("Customer profile", mapper.toSoftwareResource(specification).getName(),
                "Without a purpose characteristic the specification name is used so the purpose is non-null.");
    }

    @Test
    void toSoftwareResource_returnsNullForNullSpecification() {
        assertNull(mapper.toSoftwareResource(null), "A null specification maps to a null software resource.");
    }

    private static ProductSpecificationVO specificationWithPurpose(Object value) {
        return new ProductSpecificationVO()
                .id("spec-1").name("Customer profile")
                .productSpecCharacteristic(List.of(new ProductSpecificationCharacteristicVO()
                        .name(PURPOSE_CHARACTERISTIC)
                        .productSpecCharacteristicValue(List.of(new CharacteristicValueSpecificationVO().value(value)))));
    }
}
