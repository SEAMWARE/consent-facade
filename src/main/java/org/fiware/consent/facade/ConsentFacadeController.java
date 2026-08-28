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
package org.fiware.consent.facade;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.consent.api.CatalogApi;
import org.fiware.consent.api.ContractsApi;
import org.fiware.consent.api.ParticipantsApi;
import org.fiware.consent.exception.InvalidIdentifierException;
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

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * software-resource endpoints via the {@link CatalogMapper}. The ecosystem-contract endpoints answer
 * {@code 501} - ecosystem contracts have no TM Forum source yet.
 */
@Slf4j
@Controller("${facade.base-path:/}")
@RequiredArgsConstructor
public class ConsentFacadeController implements ContractsApi, CatalogApi, ParticipantsApi {

    /**
     * The base64 alphabets accepted for the participant path variables, each with the encoder used to
     * verify that the input round-trips.
     */
    private static final List<Base64Codec> BASE64_CODECS = List.of(
            new Base64Codec(Base64.getDecoder(), Base64.getEncoder()),
            new Base64Codec(Base64.getUrlDecoder(), Base64.getUrlEncoder()));

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

    /**
     * {@inheritDoc}
     *
     * <p>Ecosystem contracts have no TM Forum source yet. This answers {@code 501} rather than an empty
     * list, so a caller that starts depending on them fails loudly instead of concluding that this
     * participant is party to none.
     */
    @Override
    public Mono<HttpResponse<EcosystemContractListVO>> getEcosystemContractsForParticipant(String participantId, Boolean hasSigned) {
        return Mono.<HttpResponse<EcosystemContractListVO>>just(HttpResponse.status(HttpStatus.NOT_IMPLEMENTED));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Ecosystem contracts have no TM Forum source yet; see
     * {@link #getEcosystemContractsForParticipant(String, Boolean)}.
     */
    @Override
    public Mono<HttpResponse<EcosystemContractVO>> getEcosystemContract(String contractId) {
        return Mono.<HttpResponse<EcosystemContractVO>>just(HttpResponse.status(HttpStatus.NOT_IMPLEMENTED));
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
     * carried by the agreements.
     *
     * <p>Base64 is <em>required</em>, not sniffed. Falling back to using the value verbatim cannot be
     * done safely: any alphanumeric slug whose length is a multiple of four is itself valid base64 and
     * decodes into a different string, so a tolerant decoder silently compares mojibake against the
     * agreements' ids and reports a contract-free participant. Both {@code api/consent-facade.yaml}
     * and the README state that these path variables are base64-encoded, so a value that is not is a
     * caller error and gets a {@code 400}.
     *
     * @param encodedParticipantId the base64-encoded participant id, or {@code null}
     * @return the decoded self-description identifier, or {@code null} for a {@code null} input
     * @throws InvalidIdentifierException if the value is not base64, or does not decode to UTF-8
     */
    private static String decodeParticipantId(String encodedParticipantId) {
        if (encodedParticipantId == null) {
            return null;
        }
        for (Base64Codec codec : BASE64_CODECS) {
            Optional<String> decoded = codec.decode(encodedParticipantId);
            if (decoded.isPresent()) {
                return decoded.get();
            }
        }
        throw new InvalidIdentifierException(
                "A participant id must be a base64-encoded, UTF-8 self-description identifier.");
    }

    /**
     * One base64 alphabet the facade accepts. Standard base64 of a {@code did:}/{@code urn:} id can
     * contain {@code +} and {@code /}, so a caller that has to put it in a path segment may well have
     * used the URL-safe alphabet instead; both are accepted.
     *
     * @param decoder the decoder for this alphabet
     * @param encoder the matching encoder, used for the round-trip check
     */
    private record Base64Codec(Base64.Decoder decoder, Base64.Encoder encoder) {

        /**
         * Decodes {@code candidate} if it really is base64 in this alphabet: it must decode, re-encode
         * to exactly the input, and yield valid UTF-8.
         */
        private Optional<String> decode(String candidate) {
            byte[] decoded;
            try {
                decoded = decoder.decode(candidate);
            } catch (IllegalArgumentException notThisAlphabet) {
                return Optional.empty();
            }
            if (!encoder.encodeToString(decoded).equals(candidate)) {
                return Optional.empty();
            }
            return asUtf8(decoded);
        }

        /** Decodes bytes strictly, so a byte sequence that is not UTF-8 is rejected instead of mangled. */
        private static Optional<String> asUtf8(byte[] bytes) {
            try {
                return Optional.of(StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString());
            } catch (CharacterCodingException notUtf8) {
                return Optional.empty();
            }
        }
    }
}
