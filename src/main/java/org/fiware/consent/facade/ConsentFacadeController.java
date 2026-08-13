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
import org.fiware.consent.provider.TMForumClientFactory;
import org.fiware.consent.tmforum.TMForumBackedRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Implements the API provided towards the consent-manager (see api/consent-facade.yaml).
 *
 * <p>Every request is routed to the right provider's TM Forum backend (multi-provider plan,
 * {@code REQUIREMENTS.md} §11.6). Endpoints that carry a facade-minted id ({@code /bilaterals/{id}},
 * all {@code /catalog/*}, {@code /participants/{id}}) decode the {@link ProviderScopedId} and resolve
 * the provider via the {@link ProviderRegistry}; an unknown provider key yields {@code 404}. The
 * lookup endpoints that carry only a participant ({@code /bilaterals/for}, {@code /verify}) fan out
 * across <em>all</em> registered providers, since a participant may hold contracts at more than one.
 * The {@link TMForumClientFactory} turns a resolved provider into the {@link TMForumBackedRepository}
 * that reads its backend.
 *
 * <p>The bilateral-contract endpoints are projected from TM Forum agreements via the
 * {@link AgreementContractMapper}; the participant endpoint from a TM Forum organization via the
 * {@link OrganizationSelfDescriptionMapper}; and the catalog service-offering, data-resource and
 * software-resource endpoints via the {@link CatalogMapper}. The ecosystem-contract endpoints remain
 * scaffolded ({@code 404}/empty) - ecosystem contracts have no TM Forum source yet.
 */
@Slf4j
@Controller("${facade.base-path:/}")
@RequiredArgsConstructor
public class ConsentFacadeController implements ContractsApi, CatalogApi, ParticipantsApi {

    private final ProviderRegistry providerRegistry;
    private final TMForumClientFactory tmForumClientFactory;
    private final AgreementContractMapper agreementContractMapper;
    private final OrganizationSelfDescriptionMapper organizationSelfDescriptionMapper;
    private final CatalogMapper catalogMapper;

    // ---- contracts -------------------------------------------------------------------

    @Override
    public Mono<HttpResponse<BilateralContractListVO>> getBilateralContractsForParticipant(String participantId, Boolean hasSigned) {
        String participantSelfDescriptionId = decodeParticipantId(participantId);
        return projectAllContracts()
                .filter(contract -> involvesParticipant(contract, participantSelfDescriptionId))
                .filter(contract -> !requiresSigned(hasSigned) || agreementContractMapper.isSigned(contract))
                .collectList()
                .<HttpResponse<BilateralContractListVO>>map(contracts ->
                        HttpResponse.ok(new BilateralContractListVO().contracts(contracts)));
    }

    @Override
    public Mono<HttpResponse<BilateralContractVO>> getBilateralContract(String contractId) {
        return routeById(contractId, (repository, scopedId) -> repository.findAgreementById(scopedId.localId())
                .map(agreement -> agreementContractMapper.toBilateralContract(agreement, scopedId.providerKey()))
                .<HttpResponse<BilateralContractVO>>map(HttpResponse::ok)
                .defaultIfEmpty(HttpResponse.notFound()));
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
        return projectAllContracts()
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
        return routeById(id, (repository, scopedId) -> repository.findAgreementById(scopedId.localId())
                .flatMap(agreement -> repository.resolveSpecificationIds(agreement)
                        .collectList()
                        .map(specificationIds -> catalogMapper.toServiceOffering(
                                scopedId.providerKey(), scopedId.localId(), specificationIds)))
                .<HttpResponse<ServiceOfferingVO>>map(HttpResponse::ok)
                .defaultIfEmpty(HttpResponse.notFound()));
    }

    @Override
    public Mono<HttpResponse<DataResourceVO>> getDataResource(String id) {
        return routeById(id, (repository, scopedId) -> repository.findProductSpecificationById(scopedId.localId())
                .map(specification -> catalogMapper.toDataResource(scopedId.providerKey(), specification))
                .<HttpResponse<DataResourceVO>>map(HttpResponse::ok)
                .defaultIfEmpty(HttpResponse.notFound()));
    }

    @Override
    public Mono<HttpResponse<SoftwareResourceVO>> getSoftwareResource(String id) {
        // a software resource is the purpose of a product specification (its id == the spec id)
        return routeById(id, (repository, scopedId) -> repository.findProductSpecificationById(scopedId.localId())
                .map(specification -> catalogMapper.toSoftwareResource(scopedId.providerKey(), specification))
                .<HttpResponse<SoftwareResourceVO>>map(HttpResponse::ok)
                .defaultIfEmpty(HttpResponse.notFound()));
    }

    // ---- participants ----------------------------------------------------------------

    @Override
    public Mono<HttpResponse<SelfDescriptionVO>> getParticipantSelfDescription(String id) {
        // Participant self-description URLs are not provider-scoped yet (minted at registration,
        // multi-provider plan §11.7); decode tolerates both the composite and the bare form, the
        // latter resolving to the default provider.
        return routeById(id, (repository, scopedId) -> repository.findOrganizationById(scopedId.localId())
                .map(organizationSelfDescriptionMapper::toSelfDescription)
                .<HttpResponse<SelfDescriptionVO>>map(HttpResponse::ok)
                .defaultIfEmpty(HttpResponse.notFound()));
    }

    // ---- routing ---------------------------------------------------------------------

    /**
     * Projects every registered provider's agreements into bilateral contracts, each scoped to its
     * provider. Used by the participant-scoped lookups ({@code /bilaterals/for}, {@code /verify}),
     * which must consider all providers because a participant may hold contracts at more than one.
     */
    private Flux<BilateralContractVO> projectAllContracts() {
        return Flux.fromIterable(providerRegistry.all())
                .flatMap(provider -> tmForumClientFactory.forProvider(provider).findAgreements()
                        .map(agreement -> agreementContractMapper.toBilateralContract(agreement, provider.key())));
    }

    /**
     * Routes a request that carries a facade-minted, provider-scoped id: decodes the id, resolves the
     * provider, and hands the handler that provider's repository and the decoded id. An unknown
     * provider key short-circuits to {@code 404}.
     */
    private <T> Mono<HttpResponse<T>> routeById(
            String encodedId, BiFunction<TMForumBackedRepository, ProviderScopedId, Mono<HttpResponse<T>>> handler) {
        ProviderScopedId scopedId = ProviderScopedId.decode(encodedId);
        return providerRegistry.byKey(scopedId.providerKey())
                .map(provider -> handler.apply(tmForumClientFactory.forProvider(provider), scopedId))
                .orElseGet(() -> Mono.just(HttpResponse.notFound()));
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
