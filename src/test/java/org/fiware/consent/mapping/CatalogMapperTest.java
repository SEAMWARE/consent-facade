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
package org.fiware.consent.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fiware.consent.configuration.FacadeProperties;
import org.fiware.consent.model.DataResourceVO;
import org.fiware.consent.model.ServiceOfferingVO;
import org.fiware.consent.model.SoftwareResourceVO;
import org.fiware.consent.provider.ProviderConfiguration;
import org.fiware.consent.provider.ProviderRegistry;
import org.fiware.consent.provider.StaticProviderRegistry;
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
    /** A provider without its own configured self-description (producedBy falls back to the legacy static). */
    private static final String PROVIDER_KEY = "provider-x";
    /** A provider with its own configured self-description. */
    private static final String PROVIDER_WITH_SD_KEY = "provider-y";
    private static final String PROVIDER_Y_SD = SELF_URL + "/participants/provider-y~org-y";

    private final FacadeProperties facadeProperties = facadeProperties();
    private final CatalogMapper mapper =
            new CatalogMapper(new CatalogUrls(facadeProperties), facadeProperties, new ObjectMapper(), providerRegistry());

    private static FacadeProperties facadeProperties() {
        FacadeProperties facadeProperties = new FacadeProperties();
        facadeProperties.setSelfUrl(SELF_URL);
        facadeProperties.getProvider().setSelfDescription(PROVIDER_SD);
        return facadeProperties;
    }

    private static ProviderRegistry providerRegistry() {
        return new StaticProviderRegistry(List.of(
                providerConfiguration(ProviderRegistry.DEFAULT_PROVIDER_KEY, null),
                providerConfiguration(PROVIDER_KEY, null),
                providerConfiguration(PROVIDER_WITH_SD_KEY, PROVIDER_Y_SD)));
    }

    private static ProviderConfiguration providerConfiguration(String key, String selfDescription) {
        ProviderConfiguration configuration = new ProviderConfiguration(key);
        configuration.setTmforumBaseUrl("http://tm-forum-api." + key + ".svc:8080");
        configuration.setSelfDescription(selfDescription);
        return configuration;
    }

    @Test
    void toServiceOffering_bundlesSpecificationsAsDataAndSoftwareResources() {
        ServiceOfferingVO offering = mapper.toServiceOffering(PROVIDER_KEY, "agr-1", List.of("spec-1", "spec-2"));

        assertEquals(SELF_URL + "/catalog/serviceofferings/" + PROVIDER_KEY + "~agr-1", offering.getAtId(),
                "The offering @id is this facade's provider-scoped service-offering URL for the agreement.");
        assertEquals("ServiceOffering", offering.getAtType(), "The offering carries its @type.");
        assertEquals(
                List.of(SELF_URL + "/catalog/dataresources/" + PROVIDER_KEY + "~spec-1",
                        SELF_URL + "/catalog/dataresources/" + PROVIDER_KEY + "~spec-2"),
                offering.getDataResources(),
                "Every specification becomes one provider-scoped data-resource URL (the data).");
        assertEquals(
                List.of(SELF_URL + "/catalog/softwareresources/" + PROVIDER_KEY + "~spec-1",
                        SELF_URL + "/catalog/softwareresources/" + PROVIDER_KEY + "~spec-2"),
                offering.getSoftwareResources(),
                "Every specification becomes one provider-scoped software-resource URL (the purpose).");
        assertEquals(Boolean.TRUE, offering.getUserInteraction(), "Granting consent requires user interaction.");
    }

    @Test
    void toServiceOffering_withoutSpecificationsHasEmptyResources() {
        ServiceOfferingVO offering = mapper.toServiceOffering(PROVIDER_KEY, "agr-1", List.of());

        assertTrue(offering.getDataResources().isEmpty(), "An agreement without specifications bundles no data resources.");
        assertTrue(offering.getSoftwareResources().isEmpty(), "An agreement without specifications bundles no software resources.");
    }

    @Test
    void toDataResource_mapsSpecificationWithRequiredFields() {
        ProductSpecificationVO specification = new ProductSpecificationVO()
                .id("spec-1").name("Customer profile").description("The customer's profile data");

        DataResourceVO dataResource = mapper.toDataResource(PROVIDER_KEY, specification);

        assertEquals(SELF_URL + "/catalog/dataresources/" + PROVIDER_KEY + "~spec-1", dataResource.getAtId(),
                "The data-resource @id is its provider-scoped facade URL.");
        assertEquals("DataResource", dataResource.getAtType(), "The data resource carries its @type.");
        assertEquals("Customer profile", dataResource.getName(), "The name comes from the specification.");
        assertEquals("The customer's profile data", dataResource.getDescription(), "The description comes from the specification.");
        assertEquals(PROVIDER_SD, dataResource.getProducedBy(),
                "producedBy falls back to facade.provider.self-description when the provider configures none.");
        assertEquals(Boolean.TRUE, dataResource.getContainsPII(), "Consent-gated data always contains PII.");
    }

    @Test
    void toDataResource_producedByIsTheRoutedProvidersOwnSelfDescription() {
        ProductSpecificationVO specification = new ProductSpecificationVO().id("spec-1").name("Customer profile");

        DataResourceVO dataResource = mapper.toDataResource(PROVIDER_WITH_SD_KEY, specification);

        assertEquals(PROVIDER_Y_SD, dataResource.getProducedBy(),
                "producedBy is the routed provider's own configured self-description.");
    }

    @Test
    void toDataResource_returnsNullForNullSpecification() {
        assertNull(mapper.toDataResource(PROVIDER_KEY, null), "A null specification maps to a null data resource.");
    }

    @Test
    void toSoftwareResource_readsPurposeNameFromObjectCharacteristic() {
        ProductSpecificationVO specification = specificationWithPurpose(
                Map.of("id", "svc-provision", "name", "Service provision", "description", "Deliver the service"));

        SoftwareResourceVO softwareResource = mapper.toSoftwareResource(PROVIDER_KEY, specification);

        assertEquals(SELF_URL + "/catalog/softwareresources/" + PROVIDER_KEY + "~spec-1", softwareResource.getAtId(),
                "The software-resource @id is its provider-scoped facade URL.");
        assertEquals("SoftwareResource", softwareResource.getAtType(), "The software resource carries its @type.");
        assertEquals("Service provision", softwareResource.getName(), "The name is the purpose name - the consent purpose.");
        assertEquals("Deliver the service", softwareResource.getDescription(), "The description comes from the purpose.");
    }

    @Test
    void toSoftwareResource_acceptsAPlainStringPurposeValue() {
        ProductSpecificationVO specification = specificationWithPurpose("Service provision");

        assertEquals("Service provision", mapper.toSoftwareResource(PROVIDER_KEY, specification).getName(),
                "A plain-string purpose characteristic value is taken as the purpose name.");
    }

    @Test
    void toSoftwareResource_fallsBackToSpecificationNameWhenNoPurpose() {
        ProductSpecificationVO specification = new ProductSpecificationVO().id("spec-1").name("Customer profile");

        assertEquals("Customer profile", mapper.toSoftwareResource(PROVIDER_KEY, specification).getName(),
                "Without a purpose characteristic the specification name is used so the purpose is non-null.");
    }

    @Test
    void toSoftwareResource_returnsNullForNullSpecification() {
        assertNull(mapper.toSoftwareResource(PROVIDER_KEY, null), "A null specification maps to a null software resource.");
    }

    private static ProductSpecificationVO specificationWithPurpose(Object value) {
        return new ProductSpecificationVO()
                .id("spec-1").name("Customer profile")
                .productSpecCharacteristic(List.of(new ProductSpecificationCharacteristicVO()
                        .name(PURPOSE_CHARACTERISTIC)
                        .productSpecCharacteristicValue(List.of(new CharacteristicValueSpecificationVO().value(value)))));
    }
}
