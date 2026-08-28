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
package org.fiware.consent;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.fiware.consent.model.BilateralContractListVO;
import org.fiware.consent.model.BilateralContractVO;
import org.fiware.consent.model.DataResourceVO;
import org.fiware.consent.model.EcosystemContractListVO;
import org.fiware.consent.model.EcosystemContractVO;
import org.fiware.consent.model.SelfDescriptionVO;
import org.fiware.consent.model.ServiceOfferingVO;
import org.fiware.consent.model.VerificationResultVO;
import org.fiware.consent.tmforum.TMForumBackedRepository.AgreementCharacteristic;
import org.fiware.consent.tmforum.TMForumBackedRepository.EngagedPartyRole;
import org.fiware.consent.tmforum.agreement.api.AgreementApiClient;
import org.fiware.consent.tmforum.agreement.model.AgreementItemVO;
import org.fiware.consent.tmforum.agreement.model.AgreementVO;
import org.fiware.consent.tmforum.agreement.model.CharacteristicVO;
import org.fiware.consent.tmforum.agreement.model.ProductOfferingRefVO;
import org.fiware.consent.tmforum.agreement.model.RelatedPartyVO;
import org.fiware.consent.tmforum.party.api.OrganizationApiClient;
import org.fiware.consent.tmforum.party.model.MediumCharacteristicVO;
import org.fiware.consent.tmforum.party.model.OrganizationVO;
import org.fiware.consent.tmforum.party.model.ContactMediumVO;
import org.fiware.consent.tmforum.productcatalog.api.ProductOfferingApiClient;
import org.fiware.consent.tmforum.productcatalog.api.ProductSpecificationApiClient;
import org.fiware.consent.tmforum.productcatalog.model.ProductOfferingVO;
import org.fiware.consent.tmforum.productcatalog.model.ProductSpecificationRefVO;
import org.fiware.consent.tmforum.productcatalog.model.ProductSpecificationVO;
import org.fiware.consent.tmforum.productinventory.api.ProductApiClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the contract-service API the facade serves, wired over a mocked TM Forum agreement
 * client so the projection of agreements into bilateral contracts is exercised end-to-end.
 */
@MicronautTest
class ConsentFacadeControllerTest {

    private static final String PROVIDER_ID = "did:provider";
    private static final String CONSUMER_ID = "did:consumer";
    private static final long SIGNING_EPOCH_SECONDS = 1_700_000_000L;

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    AgreementApiClient agreementApiClient;

    @Inject
    OrganizationApiClient organizationApiClient;

    @Inject
    ProductOfferingApiClient productOfferingApiClient;

    @Inject
    ProductSpecificationApiClient productSpecificationApiClient;

    @MockBean(AgreementApiClient.class)
    AgreementApiClient agreementApiClient() {
        return mock(AgreementApiClient.class);
    }

    @MockBean(OrganizationApiClient.class)
    OrganizationApiClient organizationApiClient() {
        return mock(OrganizationApiClient.class);
    }

    @MockBean(ProductOfferingApiClient.class)
    ProductOfferingApiClient productOfferingApiClient() {
        return mock(ProductOfferingApiClient.class);
    }

    @MockBean(ProductSpecificationApiClient.class)
    ProductSpecificationApiClient productSpecificationApiClient() {
        return mock(ProductSpecificationApiClient.class);
    }

    @MockBean(ProductApiClient.class)
    ProductApiClient productApiClient() {
        return mock(ProductApiClient.class);
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static AgreementVO signedAgreement(String id) {
        return new AgreementVO()
                .id(id)
                .engagedParty(List.of(
                        new RelatedPartyVO().id("tmf-provider").role(EngagedPartyRole.PROVIDER),
                        new RelatedPartyVO().id("tmf-consumer").role(EngagedPartyRole.CONSUMER)))
                .characteristic(List.of(
                        new CharacteristicVO().name(AgreementCharacteristic.PROVIDER_ID).value(PROVIDER_ID),
                        new CharacteristicVO().name(AgreementCharacteristic.CONSUMER_ID).value(CONSUMER_ID),
                        new CharacteristicVO().name(AgreementCharacteristic.SIGNING_DATE).value(SIGNING_EPOCH_SECONDS)));
    }

    private void stubList(AgreementVO... agreements) {
        when(agreementApiClient.listAgreement(any(), any(), any()))
                .thenReturn(Mono.just(HttpResponse.ok(List.of(agreements))));
    }

    @Test
    void bilateralContractsForParticipant_returnsEmptyListWhenNoAgreements() {
        stubList();

        BilateralContractListVO result = client.toBlocking()
                .retrieve(HttpRequest.GET("/bilaterals/for/" + base64(PROVIDER_ID) + "?hasSigned=true"), BilateralContractListVO.class);

        assertNotNull(result.getContracts(), "The contracts list should be present.");
        assertTrue(result.getContracts().isEmpty(), "No agreements yields an empty contract list.");
    }

    @Test
    void bilateralContractsForParticipant_returnsContractsInvolvingTheParticipant() {
        stubList(signedAgreement("agreement-1"));

        BilateralContractListVO result = client.toBlocking()
                .retrieve(HttpRequest.GET("/bilaterals/for/" + base64(CONSUMER_ID) + "?hasSigned=true"), BilateralContractListVO.class);

        assertEquals(1, result.getContracts().size(), "The agreement involving the consumer should be projected.");
        assertEquals("default~agreement-1", result.getContracts().get(0).getId(),
                "The contract id is the agreement id scoped to the default provider.");
        assertEquals("signed", result.getContracts().get(0).getStatus(), "The signed agreement maps to a signed contract.");
    }

    @Test
    void bilateralContractsForParticipant_filtersOutParticipantsNotInvolved() {
        stubList(signedAgreement("agreement-1"));

        BilateralContractListVO result = client.toBlocking()
                .retrieve(HttpRequest.GET("/bilaterals/for/" + base64("did:stranger") + "?hasSigned=true"), BilateralContractListVO.class);

        assertTrue(result.getContracts().isEmpty(), "An uninvolved participant sees no contracts.");
    }

    @Test
    void getBilateralContract_returnsContract() {
        when(agreementApiClient.retrieveAgreement(eq("agreement-1"), any()))
                .thenReturn(Mono.just(HttpResponse.ok(signedAgreement("agreement-1"))));

        BilateralContractVO contract = client.toBlocking()
                .retrieve(HttpRequest.GET("/bilaterals/default~agreement-1"), BilateralContractVO.class);

        assertEquals("default~agreement-1", contract.getId(), "The requested agreement should be projected.");
        assertEquals(PROVIDER_ID, contract.getDataProvider(), "The data provider should be resolved.");
    }

    @Test
    void getBilateralContract_returns404ForAnUnknownProvider() {
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().retrieve(HttpRequest.GET("/bilaterals/unregistered~agreement-1"), BilateralContractVO.class));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus(),
                "A contract id scoped to an unregistered provider cannot be routed and maps to 404.");
    }

    @Test
    void getBilateralContract_resolvesABareLegacyId() {
        when(agreementApiClient.retrieveAgreement(eq("agreement-1"), any()))
                .thenReturn(Mono.just(HttpResponse.ok(signedAgreement("agreement-1"))));

        BilateralContractVO contract = client.toBlocking()
                .retrieve(HttpRequest.GET("/bilaterals/agreement-1"), BilateralContractVO.class);

        assertEquals("default~agreement-1", contract.getId(),
                "A bare id (no provider key) resolves under the default provider.");
    }

    @Test
    void getBilateralContract_returns404WhenAgreementMissing() {
        when(agreementApiClient.retrieveAgreement(eq("missing"), any()))
                .thenReturn(Mono.error(new HttpClientResponseException("Not Found", HttpResponse.notFound())));

        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().retrieve(HttpRequest.GET("/bilaterals/missing"), BilateralContractVO.class));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus(), "A missing agreement should map to 404.");
    }

    @Test
    void verifyContract_isVerifiedWhenSignedContractExists() {
        stubList(signedAgreement("agreement-1"));

        VerificationResultVO result = client.toBlocking()
                .retrieve(HttpRequest.GET("/verify/" + base64(PROVIDER_ID) + "/" + base64(CONSUMER_ID)), VerificationResultVO.class);

        assertTrue(result.getVerified(), "A signed contract between the two parties should verify.");
        assertEquals(1, result.getContracts().size(), "The verifying contract should be returned.");
    }

    @Test
    void verifyContract_isNotVerifiedForUnrelatedParties() {
        stubList(signedAgreement("agreement-1"));

        VerificationResultVO result = client.toBlocking()
                .retrieve(HttpRequest.GET("/verify/" + base64(PROVIDER_ID) + "/" + base64("did:stranger")), VerificationResultVO.class);

        assertFalse(result.getVerified(), "No contract between the two parties should not verify.");
        assertTrue(result.getContracts().isEmpty(), "No verifying contracts should be returned.");
    }

    @Test
    void getParticipantSelfDescription_returnsSelfDescription() {
        OrganizationVO organization = new OrganizationVO()
                .name("ACME Data Ltd")
                .contactMedium(List.of(new ContactMediumVO().characteristic(new MediumCharacteristicVO().country("DE"))));
        when(organizationApiClient.retrieveOrganization(eq("org-1"), any()))
                .thenReturn(Mono.just(HttpResponse.ok(organization)));

        SelfDescriptionVO result = client.toBlocking()
                .retrieve(HttpRequest.GET("/participants/org-1"), SelfDescriptionVO.class);

        assertEquals("ACME Data Ltd", result.getLegalName(), "The organization should be projected into a self-description.");
        assertEquals("DE", result.getLegalPerson().getLegalAddress().getCountryCode(),
                "The legal address country code should be resolved for the receipt builder.");
    }

    @Test
    void getParticipantSelfDescription_returns404WhenOrganizationMissing() {
        when(organizationApiClient.retrieveOrganization(eq("missing"), any()))
                .thenReturn(Mono.error(new HttpClientResponseException("Not Found", HttpResponse.notFound())));

        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().retrieve(HttpRequest.GET("/participants/missing"), SelfDescriptionVO.class));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus(), "A missing organization should map to 404.");
    }

    @Test
    void getServiceOffering_bundlesAllAgreementSpecificationsAsDataResources() {
        AgreementVO agreement = new AgreementVO().id("agr-1").agreementItem(List.of(
                new AgreementItemVO().productOffering(List.of(new ProductOfferingRefVO().id("off-1")))));
        when(agreementApiClient.retrieveAgreement(eq("agr-1"), any()))
                .thenReturn(Mono.just(HttpResponse.ok(agreement)));
        when(productOfferingApiClient.retrieveProductOffering(eq("off-1"), any()))
                .thenReturn(Mono.just(HttpResponse.ok(new ProductOfferingVO().id("off-1")
                        .productSpecification(new ProductSpecificationRefVO().id("spec-1")))));

        ServiceOfferingVO offering = client.toBlocking()
                .retrieve(HttpRequest.GET("/catalog/serviceofferings/default~agr-1"), ServiceOfferingVO.class);

        assertEquals(1, offering.getDataResources().size(), "The agreement's single specification is bundled.");
        assertTrue(offering.getDataResources().get(0).endsWith("/catalog/dataresources/default~spec-1"),
                "The data resource points at the specification's provider-scoped facade URL.");
    }

    @Test
    void getServiceOffering_returns404WhenAgreementMissing() {
        when(agreementApiClient.retrieveAgreement(eq("missing"), any()))
                .thenReturn(Mono.error(new HttpClientResponseException("Not Found", HttpResponse.notFound())));

        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().retrieve(HttpRequest.GET("/catalog/serviceofferings/missing"), ServiceOfferingVO.class));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus(), "A missing agreement should map to 404.");
    }

    @Test
    void getServiceOffering_returns404ForAnUnknownProvider() {
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().retrieve(HttpRequest.GET("/catalog/serviceofferings/unregistered~agr-1"), ServiceOfferingVO.class));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus(),
                "A catalog id scoped to an unregistered provider cannot be routed and maps to 404.");
    }

    @Test
    void getDataResource_returnsMappedSpecification() {
        when(productSpecificationApiClient.retrieveProductSpecification(eq("spec-1"), any()))
                .thenReturn(Mono.just(HttpResponse.ok(new ProductSpecificationVO().id("spec-1").name("Customer profile"))));

        DataResourceVO dataResource = client.toBlocking()
                .retrieve(HttpRequest.GET("/catalog/dataresources/spec-1"), DataResourceVO.class);

        assertEquals("Customer profile", dataResource.getName(), "The data resource is the mapped specification.");
    }

    @Test
    void participantEndpoints_rejectAnIdThatIsNotBase64() {
        stubList(signedAgreement("agr-1"));

        // 'not base64!' is outside the alphabet. The old tolerant decoder used such a value verbatim,
        // which meant a caller could never tell a malformed id from a participant without contracts.
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().retrieve(
                        HttpRequest.GET("/bilaterals/for/not%20base64%21"), BilateralContractListVO.class));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus(),
                "The spec says these path variables are base64; a value that is not is a caller error.");
    }

    @Test
    void participantEndpoints_acceptUrlSafeBase64() {
        stubList(signedAgreement("agr-1"));
        String urlSafe = Base64.getUrlEncoder().encodeToString(PROVIDER_ID.getBytes(StandardCharsets.UTF_8));

        BilateralContractListVO result = client.toBlocking()
                .retrieve(HttpRequest.GET("/bilaterals/for/" + urlSafe), BilateralContractListVO.class);

        assertEquals(1, result.getContracts().size(),
                "standard base64 of a did/urn contains '+' and '/', so a caller may well have used the URL-safe alphabet");
    }

    @Test
    void catalogEndpoints_rejectAnIdCarryingARequestInjectionCharacter() {
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().retrieve(
                        HttpRequest.GET("/catalog/dataresources/spec%3Ffields%3D%2A"), DataResourceVO.class));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus(),
                "An id that could shape the outbound TM Forum request is refused at the edge.");
    }

    @Test
    void catalogEndpoints_rejectAnIdCarryingATraversalSegment() {
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().retrieve(
                        HttpRequest.GET("/catalog/dataresources/..%2Factuator"), DataResourceVO.class));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus(), "Traversal is refused at the edge.");
    }

    @Test
    void ecosystemContractsForParticipant_answerAnEmptyListSoTheNoticeFanOutSurvives() {
        // The consent-manager fetches a participant's bilateral AND ecosystem contracts together in
        // one Promise.all and cannot opt out of the ecosystem call, so a 501 here rejects the whole
        // fan-out and every privacy-notice lookup fails with an opaque 500.
        HttpResponse<EcosystemContractListVO> response = client.toBlocking().exchange(
                HttpRequest.GET("/contracts/for/" + base64(PROVIDER_ID)), EcosystemContractListVO.class);

        assertEquals(HttpStatus.OK, response.getStatus(), "A 501 would take the notice lookup down.");
        assertEquals(List.of(), response.body().getContracts(),
                "There is no TM Forum source for ecosystem contracts, so the participant is party to none.");
    }

    @Test
    void ecosystemContractById_isStillNotImplemented() {
        // Nothing fans out into the by-id lookup: it is only reached for a contract that some
        // listing returned, and the listing above returns none. A 501 there stays honest.
        HttpClientResponseException byId = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(HttpRequest.GET("/contracts/agr-1"), EcosystemContractVO.class));

        assertEquals(HttpStatus.NOT_IMPLEMENTED, byId.getStatus());
    }

    @Test
    void aBackendFailureBecomesABadGatewayWithoutForwardingItsDetail() {
        when(agreementApiClient.retrieveAgreement(eq("agr-1"), any()))
                .thenReturn(Mono.error(new HttpClientResponseException(
                        "Unauthorized", HttpResponse.unauthorized().body("internal backend detail"))));

        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().retrieve(HttpRequest.GET("/bilaterals/agr-1"), BilateralContractVO.class));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus(),
                "A provider backend's status must not be reflected onto the consent-manager-facing API.");
        assertFalse(exception.getResponse().getBody(String.class).orElse("").contains("internal backend detail"),
                "and neither must its error body");
    }
}
