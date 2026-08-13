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
import org.fiware.consent.provider.ProviderRegistry;
import org.fiware.consent.provider.ProviderScopedId;
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
    private final ProviderRegistry providerRegistry;

    // ---- contracts -------------------------------------------------------------------

    @Override
    public Mono<HttpResponse<BilateralContractListVO>> getBilateralContractsForParticipant(String participantId, Boolean hasSigned) {
        String participantSelfDescriptionId = decodeParticipantId(participantId);
        // The agreements are read from a single (default) TM Forum backend until per-provider
        // routing is wired (multi-provider plan, REQUIREMENTS.md §11.6), so the contracts minted here
        // are scoped to the default provider.
        String providerKey = defaultProviderKey();
        return repository.findAgreements()
                .map(agreement -> agreementContractMapper.toBilateralContract(agreement, providerKey))
                .filter(contract -> involvesParticipant(contract, participantSelfDescriptionId))
                .filter(contract -> !requiresSigned(hasSigned) || agreementContractMapper.isSigned(contract))
                .collectList()
                .<HttpResponse<BilateralContractListVO>>map(contracts ->
                        HttpResponse.ok(new BilateralContractListVO().contracts(contracts)));
    }

    @Override
    public Mono<HttpResponse<BilateralContractVO>> getBilateralContract(String contractId) {
        ProviderScopedId scopedId = ProviderScopedId.decode(contractId);
        return repository.findAgreementById(scopedId.localId())
                .map(agreement -> agreementContractMapper.toBilateralContract(agreement, scopedId.providerKey()))
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
        String providerKey = defaultProviderKey();
        return repository.findAgreements()
                .map(agreement -> agreementContractMapper.toBilateralContract(agreement, providerKey))
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
        ProviderScopedId scopedId = ProviderScopedId.decode(id);
        return repository.findAgreementById(scopedId.localId())
                .flatMap(agreement -> repository.resolveSpecificationIds(agreement)
                        .collectList()
                        .map(specificationIds -> catalogMapper.toServiceOffering(
                                scopedId.providerKey(), scopedId.localId(), specificationIds)))
                .<HttpResponse<ServiceOfferingVO>>map(HttpResponse::ok)
                .defaultIfEmpty(HttpResponse.notFound());
    }

    @Override
    public Mono<HttpResponse<DataResourceVO>> getDataResource(String id) {
        ProviderScopedId scopedId = ProviderScopedId.decode(id);
        return repository.findProductSpecificationById(scopedId.localId())
                .map(specification -> catalogMapper.toDataResource(scopedId.providerKey(), specification))
                .<HttpResponse<DataResourceVO>>map(HttpResponse::ok)
                .defaultIfEmpty(HttpResponse.notFound());
    }

    @Override
    public Mono<HttpResponse<SoftwareResourceVO>> getSoftwareResource(String id) {
        // a software resource is the purpose of a product specification (its id == the spec id)
        ProviderScopedId scopedId = ProviderScopedId.decode(id);
        return repository.findProductSpecificationById(scopedId.localId())
                .map(specification -> catalogMapper.toSoftwareResource(scopedId.providerKey(), specification))
                .<HttpResponse<SoftwareResourceVO>>map(HttpResponse::ok)
                .defaultIfEmpty(HttpResponse.notFound());
    }

    // ---- participants ----------------------------------------------------------------

    @Override
    public Mono<HttpResponse<SelfDescriptionVO>> getParticipantSelfDescription(String id) {
        // Participant self-description URLs are not provider-scoped yet (minted at registration,
        // multi-provider plan §11.7); decode tolerates both the composite and the bare form.
        ProviderScopedId scopedId = ProviderScopedId.decode(id);
        return repository.findOrganizationById(scopedId.localId())
                .map(organizationSelfDescriptionMapper::toSelfDescription)
                .<HttpResponse<SelfDescriptionVO>>map(HttpResponse::ok)
                .defaultIfEmpty(HttpResponse.notFound());
    }

    // ---- helpers ---------------------------------------------------------------------

    /**
     * Key of the provider whose backend the facade currently reads from. Until per-request routing
     * (multi-provider plan §11.6) is wired, everything is served from the default provider.
     */
    private String defaultProviderKey() {
        return providerRegistry.defaultProvider().key();
    }

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
