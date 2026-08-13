package org.fiware.consent.facade;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.consent.api.CatalogApi;
import org.fiware.consent.api.ContractsApi;
import org.fiware.consent.api.ParticipantsApi;
import org.fiware.consent.mapping.AgreementContractMapper;
import org.fiware.consent.mapping.CatalogMapper;
import org.fiware.consent.mapping.OrganizationSelfDescriptionMapper;
import org.fiware.consent.model.BilateralContractListVO;
import org.fiware.consent.model.BilateralContractVO;
import org.fiware.consent.model.DataResourceVO;
import org.fiware.consent.model.EcosystemContractListVO;
import org.fiware.consent.model.EcosystemContractVO;
import org.fiware.consent.model.SelfDescriptionVO;
import org.fiware.consent.model.ServiceOfferingVO;
import org.fiware.consent.model.SoftwareResourceVO;
import org.fiware.consent.model.VerificationResultVO;
import org.fiware.consent.tmforum.TMForumBackedRepository;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Implements the API provided towards the consent-manager (see api/consent-facade.yaml).
 *
 * <p>The bilateral-contract endpoints are projected from TM Forum agreements: the
 * {@link TMForumBackedRepository} reads the agreements the EDC extension writes and the
 * {@link AgreementContractMapper} maps each into a {@link BilateralContractVO}.
 *
 * <p>The participant endpoint is projected from a TM Forum organization via the
 * {@link OrganizationSelfDescriptionMapper}. The catalog service-offering, data-resource and
 * software-resource endpoints are projected via the {@link CatalogMapper}: an agreement's service
 * offering bundles all of the agreement's product specifications as {@code dataResources} (the data)
 * and {@code softwareResources} (the purposes); each data resource is a mapped product specification
 * and each software resource is that specification's purpose characteristic. The ecosystem-contract
 * endpoints remain scaffolded ({@code 404}/empty) - ecosystem contracts have no TM Forum source yet.
 */
@Slf4j
@Controller("${facade.base-path:/}")
@RequiredArgsConstructor
public class ConsentFacadeController implements ContractsApi, CatalogApi, ParticipantsApi {

    private final TMForumBackedRepository repository;
    private final AgreementContractMapper agreementContractMapper;
    private final OrganizationSelfDescriptionMapper organizationSelfDescriptionMapper;
    private final CatalogMapper catalogMapper;

    // ---- contracts -------------------------------------------------------------------

    @Override
    public Mono<HttpResponse<BilateralContractListVO>> getBilateralContractsForParticipant(String participantId, Boolean hasSigned) {
        String participantSelfDescriptionId = decodeParticipantId(participantId);
        return repository.findAgreements()
                .map(agreementContractMapper::toBilateralContract)
                .filter(contract -> involvesParticipant(contract, participantSelfDescriptionId))
                .filter(contract -> !requiresSigned(hasSigned) || agreementContractMapper.isSigned(contract))
                .collectList()
                .<HttpResponse<BilateralContractListVO>>map(contracts ->
                        HttpResponse.ok(new BilateralContractListVO().contracts(contracts)));
    }

    @Override
    public Mono<HttpResponse<BilateralContractVO>> getBilateralContract(String contractId) {
        return repository.findAgreementById(contractId)
                .map(agreementContractMapper::toBilateralContract)
                .<HttpResponse<BilateralContractVO>>map(HttpResponse::ok)
                .defaultIfEmpty(HttpResponse.notFound());
    }

    @Override
    public Mono<HttpResponse<EcosystemContractListVO>> getEcosystemContractsForParticipant(String participantId, Boolean hasSigned) {
        // Ecosystem contracts are not modelled by TM Forum yet - return empty for now.
        return Mono.<HttpResponse<EcosystemContractListVO>>just(HttpResponse.ok(new EcosystemContractListVO().contracts(List.of())));
    }

    @Override
    public Mono<HttpResponse<EcosystemContractVO>> getEcosystemContract(String contractId) {
        return Mono.<HttpResponse<EcosystemContractVO>>just(HttpResponse.notFound());
    }

    @Override
    public Mono<HttpResponse<VerificationResultVO>> verifyContract(String providerId, String consumerId) {
        String providerSelfDescriptionId = decodeParticipantId(providerId);
        String consumerSelfDescriptionId = decodeParticipantId(consumerId);
        return repository.findAgreements()
                .map(agreementContractMapper::toBilateralContract)
                .filter(agreementContractMapper::isSigned)
                .filter(contract -> Objects.equals(contract.getDataProvider(), providerSelfDescriptionId)
                        && Objects.equals(contract.getDataConsumer(), consumerSelfDescriptionId))
                .collectList()
                .<HttpResponse<VerificationResultVO>>map(contracts ->
                        HttpResponse.ok(new VerificationResultVO().verified(!contracts.isEmpty()).contracts(contracts)));
    }

    // ---- catalog ---------------------------------------------------------------------

    @Override
    public Mono<HttpResponse<ServiceOfferingVO>> getServiceOffering(String id) {
        // one contract = one agreement = one service offering bundling all of the agreement's specifications
        return repository.findAgreementById(id)
                .flatMap(agreement -> repository.resolveSpecificationIds(agreement)
                        .collectList()
                        .map(specificationIds -> catalogMapper.toServiceOffering(id, specificationIds)))
                .<HttpResponse<ServiceOfferingVO>>map(HttpResponse::ok)
                .defaultIfEmpty(HttpResponse.notFound());
    }

    @Override
    public Mono<HttpResponse<DataResourceVO>> getDataResource(String id) {
        return repository.findProductSpecificationById(id)
                .map(catalogMapper::toDataResource)
                .<HttpResponse<DataResourceVO>>map(HttpResponse::ok)
                .defaultIfEmpty(HttpResponse.notFound());
    }

    @Override
    public Mono<HttpResponse<SoftwareResourceVO>> getSoftwareResource(String id) {
        // a software resource is the purpose of a product specification (its id == the spec id)
        return repository.findProductSpecificationById(id)
                .map(catalogMapper::toSoftwareResource)
                .<HttpResponse<SoftwareResourceVO>>map(HttpResponse::ok)
                .defaultIfEmpty(HttpResponse.notFound());
    }

    // ---- participants ----------------------------------------------------------------

    @Override
    public Mono<HttpResponse<SelfDescriptionVO>> getParticipantSelfDescription(String id) {
        return repository.findOrganizationById(id)
                .map(organizationSelfDescriptionMapper::toSelfDescription)
                .<HttpResponse<SelfDescriptionVO>>map(HttpResponse::ok)
                .defaultIfEmpty(HttpResponse.notFound());
    }

    // ---- helpers ---------------------------------------------------------------------

    private static boolean involvesParticipant(BilateralContractVO contract, String participantSelfDescriptionId) {
        return Objects.equals(contract.getDataProvider(), participantSelfDescriptionId)
                || Objects.equals(contract.getDataConsumer(), participantSelfDescriptionId);
    }

    private static boolean requiresSigned(Boolean hasSigned) {
        return Boolean.TRUE.equals(hasSigned);
    }

    /**
     * Decodes a participant identifier from the base64 form the consent-manager passes on the
     * {@code /for/{participantId}} and {@code /verify} paths into the self-description identifier
     * carried by the agreements. A value that is not valid base64 is used verbatim.
     */
    private String decodeParticipantId(String encodedParticipantId) {
        if (encodedParticipantId == null) {
            return null;
        }
        try {
            return new String(Base64.getDecoder().decode(encodedParticipantId), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.debug("Participant id '{}' is not valid base64, using it verbatim.", encodedParticipantId);
            return encodedParticipantId;
        }
    }
}
