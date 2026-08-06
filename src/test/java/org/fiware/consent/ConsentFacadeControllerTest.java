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
import org.fiware.consent.model.SelfDescriptionVO;
import org.fiware.consent.model.VerificationResultVO;
import org.fiware.consent.tmforum.TMForumBackedRepository.AgreementCharacteristic;
import org.fiware.consent.tmforum.TMForumBackedRepository.EngagedPartyRole;
import org.fiware.consent.tmforum.agreement.api.AgreementApiClient;
import org.fiware.consent.tmforum.agreement.model.AgreementVO;
import org.fiware.consent.tmforum.agreement.model.CharacteristicVO;
import org.fiware.consent.tmforum.agreement.model.RelatedPartyVO;
import org.fiware.consent.tmforum.party.api.OrganizationApiClient;
import org.fiware.consent.tmforum.party.model.MediumCharacteristicVO;
import org.fiware.consent.tmforum.party.model.OrganizationVO;
import org.fiware.consent.tmforum.party.model.ContactMediumVO;
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

    @MockBean(AgreementApiClient.class)
    AgreementApiClient agreementApiClient() {
        return mock(AgreementApiClient.class);
    }

    @MockBean(OrganizationApiClient.class)
    OrganizationApiClient organizationApiClient() {
        return mock(OrganizationApiClient.class);
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
        assertEquals("agreement-1", result.getContracts().get(0).getId(), "The contract id is the agreement id.");
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
                .retrieve(HttpRequest.GET("/bilaterals/agreement-1"), BilateralContractVO.class);

        assertEquals("agreement-1", contract.getId(), "The requested agreement should be projected.");
        assertEquals(PROVIDER_ID, contract.getDataProvider(), "The data provider should be resolved.");
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
}
