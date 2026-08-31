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
import org.fiware.consent.model.BilateralContractVO;
import org.fiware.consent.model.OdrlPolicyVO;
import org.fiware.consent.model.OdrlRuleVO;
import org.fiware.consent.tmforum.TMForumBackedRepository.AgreementCharacteristic;
import org.fiware.consent.tmforum.TMForumBackedRepository.EngagedPartyRole;
import org.fiware.consent.tmforum.agreement.model.AgreementVO;
import org.fiware.consent.tmforum.agreement.model.CharacteristicVO;
import org.fiware.consent.tmforum.agreement.model.RelatedPartyVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AgreementContractMapper}: the projection of an EDC-written TM Forum agreement
 * into the bilateral contract the consent-manager consumes.
 */
class AgreementContractMapperTest {

    private static final Instant INITIAL_DATE = Instant.parse("2026-01-02T03:04:05Z");
    private static final long SIGNING_EPOCH_SECONDS = 1_700_000_000L;
    private static final String SELF_URL = "http://facade.example";
    private static final String PROVIDER_KEY = "provider-x";
    private static final String OFFERING_URL = SELF_URL + "/catalog/serviceofferings/" + PROVIDER_KEY + "~agreement-1";

    private final AgreementContractMapper mapper = new AgreementContractMapper(new ObjectMapper(), catalogUrls(), new OdrlNormalizer());

    private static CatalogUrls catalogUrls() {
        FacadeProperties facadeProperties = new FacadeProperties();
        facadeProperties.setSelfUrl(SELF_URL);
        return new CatalogUrls(facadeProperties);
    }

    private static CharacteristicVO characteristic(String name, Object value) {
        return new CharacteristicVO().name(name).value(value);
    }

    private static Map<String, Object> odrlPolicy() {
        return Map.of(
                "@type", "Set",
                "uid", "urn:policy:1",
                "permission", List.of(Map.of(
                        "target", "urn:asset:1",
                        "action", "use",
                        "assigner", "did:provider",
                        "assignee", "did:consumer")));
    }

    /** An agreement shaped like the one {@code TMFEdcMapper#toAgreement} produces. */
    private static AgreementVO edcAgreement() {
        return new AgreementVO()
                .id("agreement-1")
                .initialDate(INITIAL_DATE)
                .engagedParty(List.of(
                        new RelatedPartyVO().id("tmf-provider").role(EngagedPartyRole.PROVIDER),
                        new RelatedPartyVO().id("tmf-consumer").role(EngagedPartyRole.CONSUMER)))
                .characteristic(List.of(
                        characteristic(AgreementCharacteristic.ASSET_ID, "urn:asset:1"),
                        characteristic(AgreementCharacteristic.PROVIDER_ID, "did:provider"),
                        characteristic(AgreementCharacteristic.CONSUMER_ID, "did:consumer"),
                        characteristic(AgreementCharacteristic.POLICY, odrlPolicy()),
                        characteristic(AgreementCharacteristic.SIGNING_DATE, SIGNING_EPOCH_SECONDS)));
    }

    @Test
    void toBilateralContract_mapsEdcAgreement() {
        BilateralContractVO contract = mapper.toBilateralContract(edcAgreement(), PROVIDER_KEY);

        assertEquals(PROVIDER_KEY + "~agreement-1", contract.getId(),
                "The contract _id is the provider-scoped agreement id.");
        assertEquals(PROVIDER_KEY + "~agreement-1", contract.getUid(),
                "The contract uid is the provider-scoped agreement id.");
        assertEquals("signed", contract.getStatus(), "An agreement with a signing date is a signed contract.");
        assertEquals("did:provider", contract.getDataProvider(), "dataProvider comes from the provider-id characteristic.");
        assertEquals("did:consumer", contract.getDataConsumer(), "dataConsumer comes from the consumer-id characteristic.");
        assertEquals(INITIAL_DATE, contract.getCreatedAt(), "createdAt is the agreement initial date.");
        assertEquals(Instant.ofEpochSecond(SIGNING_EPOCH_SECONDS), contract.getUpdatedAt(),
                "updatedAt is the signing date (epoch seconds).");
        assertEquals(OFFERING_URL, contract.getServiceOffering(),
                "serviceOffering points at this facade's provider-scoped catalog endpoint for the agreement.");
        assertEquals("agreement-1", contract.getProfile(), "profile carries the agreement id as the privacy-notice title.");
        assertNotNull(contract.getPurpose(), "The contract carries a purpose.");
        assertEquals(1, contract.getPurpose().size(), "One agreement offering maps to one purpose.");
        assertEquals(OFFERING_URL, contract.getPurpose().get(0).getPurpose(),
                "purpose[].purpose points at the same offering URL, whose softwareResources carry the purpose.");
    }

    /**
     * The JSON-LD ODRL the ODRL PAP requires - the form a provider actually declares on the product
     * specification, so the one declaration serves both the PAP and consent.
     */
    private static Map<String, Object> papOdrlPolicy() {
        return Map.of(
                "@context", Map.of("odrl", "http://www.w3.org/ns/odrl/2/"),
                "@id", "https://provider.org/policy/1",
                "@type", "odrl:Policy",
                "odrl:uid", "urn:policy:1",
                // JSON-LD writes a single permission as an object, not an array
                "odrl:permission", Map.of(
                        "odrl:assigner", Map.of("@id", "did:provider"),
                        "odrl:assignee", Map.of("@id", "did:consumer"),
                        "odrl:target", Map.of("@id", "urn:asset:1"),
                        "odrl:action", Map.of("@id", "odrl:use")));
    }

    private static AgreementVO papPolicyAgreement() {
        return new AgreementVO()
                .id("agreement-1")
                .initialDate(INITIAL_DATE)
                .characteristic(List.of(
                        characteristic(AgreementCharacteristic.PROVIDER_ID, "did:provider"),
                        characteristic(AgreementCharacteristic.CONSUMER_ID, "did:consumer"),
                        characteristic(AgreementCharacteristic.POLICY, papOdrlPolicy()),
                        characteristic(AgreementCharacteristic.SIGNING_DATE, SIGNING_EPOCH_SECONDS)));
    }

    @Test
    void toBilateralContract_convertsThePapsJsonLdOdrlPolicy() {
        // the provider declares ODRL once, in the form the PAP requires; the facade must read it
        BilateralContractVO contract = mapper.toBilateralContract(papPolicyAgreement(), PROVIDER_KEY);

        assertNotNull(contract.getPolicy(), "The JSON-LD policy must be understood, not silently dropped.");
        OdrlPolicyVO policy = contract.getPolicy().get(0);
        assertEquals("urn:policy:1", policy.getUid(), "odrl:uid binds to uid.");
        assertEquals(1, policy.getPermission().size(),
                "A single permission written as an object becomes a one-element array.");
        OdrlRuleVO rule = policy.getPermission().get(0);
        assertEquals("urn:asset:1", rule.getAssetTarget(),
                "odrl:target {@id} collapses to the asset URI, which is what a data-plane enforcer matches.");
        assertEquals(OFFERING_URL, rule.getTarget(), "The target is still retargeted to the offering URL.");
        assertEquals("odrl:use", rule.getAction(), "odrl:action {@id} collapses to the action.");
        assertEquals("did:provider", rule.getAssigner());
        assertEquals("did:consumer", rule.getAssignee());
        assertNotNull(policy.getProhibition(), "prohibition is always an array for the consent-manager.");
    }

    @Test
    void toBilateralContract_collapsesAnAssetCollectionToItsSource() {
        // an AssetCollection has no single URI; its source is the closest thing the model can carry,
        // and the refinements that actually narrow it are lost
        Map<String, Object> collectionPolicy = Map.of(
                "odrl:uid", "urn:policy:collection",
                "odrl:permission", Map.of(
                        "odrl:target", Map.of(
                                "@type", "odrl:AssetCollection",
                                "odrl:source", "urn:asset",
                                "odrl:refinement", List.of(Map.of(
                                        "@type", "odrl:Constraint",
                                        "odrl:leftOperand", "ngsi-ld:entityType",
                                        "odrl:operator", Map.of("@id", "odrl:eq"),
                                        "odrl:rightOperand", "PersonalProfile"))),
                        "odrl:action", Map.of("@id", "odrl:read")));
        AgreementVO agreement = new AgreementVO()
                .id("agreement-2")
                .characteristic(List.of(
                        characteristic(AgreementCharacteristic.PROVIDER_ID, "did:provider"),
                        characteristic(AgreementCharacteristic.CONSUMER_ID, "did:consumer"),
                        characteristic(AgreementCharacteristic.POLICY, collectionPolicy),
                        characteristic(AgreementCharacteristic.SIGNING_DATE, SIGNING_EPOCH_SECONDS)));

        OdrlRuleVO rule = mapper.toBilateralContract(agreement, PROVIDER_KEY)
                .getPolicy().get(0).getPermission().get(0);

        assertEquals("urn:asset", rule.getAssetTarget(),
                "The collection collapses to its source - a plain-URI enforcer will not match a "
                        + "concrete object against it, which is a limit of the contract model.");
    }

    @Test
    void toBilateralContract_convertsOdrlPolicy() {
        BilateralContractVO contract = mapper.toBilateralContract(edcAgreement(), PROVIDER_KEY);

        assertNotNull(contract.getPolicy(), "The contract should carry the ODRL policy.");
        assertEquals(1, contract.getPolicy().size(), "The single agreement policy maps to one contract policy.");
        OdrlPolicyVO policy = contract.getPolicy().get(0);
        assertEquals("Set", policy.getAtType(), "The policy @type is carried over.");
        assertEquals("urn:policy:1", policy.getUid(), "The policy uid is carried over.");
        assertEquals(1, policy.getPermission().size(), "The permission rule is carried over.");
        assertEquals(OFFERING_URL, policy.getPermission().get(0).getTarget(),
                "The permission target is retargeted to the service-offering URL so the consent-manager's "
                        + "serviceOffering.includes(target) data-chain check matches.");
        assertEquals("use", policy.getPermission().get(0).getAction(), "The permission action is carried over.");
        assertNotNull(policy.getProhibition(), "prohibition is always a (possibly empty) array - the consent-manager maps over it.");
        assertTrue(policy.getProhibition().isEmpty(), "an EDC policy without a prohibition maps to an empty prohibition list.");
        assertEquals("urn:asset:1", policy.getPermission().get(0).getAssetTarget(),
                "The source ODRL target (the asset URI) is preserved in assetTarget so a data-plane "
                        + "enforcer can match it against the requested resource.");
    }

    @Test
    void toBilateralContract_fallsBackToEngagedPartiesWhenCharacteristicsAbsent() {
        AgreementVO agreement = new AgreementVO()
                .id("agreement-2")
                .engagedParty(List.of(
                        new RelatedPartyVO().id("tmf-provider").role(EngagedPartyRole.PROVIDER),
                        new RelatedPartyVO().id("tmf-consumer").role(EngagedPartyRole.CONSUMER)));

        BilateralContractVO contract = mapper.toBilateralContract(agreement, PROVIDER_KEY);

        assertEquals("tmf-provider", contract.getDataProvider(), "dataProvider falls back to the Provider engaged party.");
        assertEquals("tmf-consumer", contract.getDataConsumer(), "dataConsumer falls back to the Consumer engaged party.");
        assertNull(contract.getPolicy(), "A contract without a policy characteristic carries no policy.");
    }

    @Test
    void toBilateralContract_returnsNullForNullAgreement() {
        assertNull(mapper.toBilateralContract(null, PROVIDER_KEY), "A null agreement maps to a null contract.");
    }

    @ParameterizedTest
    @CsvSource({
            "approved,signed",
            "accepted,signed",
            "active,signed",
            "completed,signed",
            "rejected,terminated",
            "cancelled,terminated",
            "revoked,revoked",
            "inProgress,pending",
            "draft,draft",
            "somethingUnknown,pending"
    })
    void toBilateralContract_mapsStatusWhenNotSigned(String agreementStatus, String expectedContractStatus) {
        AgreementVO agreement = new AgreementVO().id("agreement-3").status(agreementStatus);

        BilateralContractVO contract = mapper.toBilateralContract(agreement, PROVIDER_KEY);

        assertEquals(expectedContractStatus, contract.getStatus(),
                "Agreement status '" + agreementStatus + "' should map to contract status '" + expectedContractStatus + "'.");
    }
}
