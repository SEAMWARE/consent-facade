package org.fiware.consent.mapping;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.fiware.consent.model.BilateralContractVO;
import org.fiware.consent.model.OdrlPolicyVO;
import org.fiware.consent.tmforum.TMForumBackedRepository;
import org.fiware.consent.tmforum.TMForumBackedRepository.AgreementCharacteristic;
import org.fiware.consent.tmforum.TMForumBackedRepository.EngagedPartyRole;
import org.fiware.consent.tmforum.agreement.model.AgreementVO;
import org.fiware.consent.tmforum.agreement.model.RelatedPartyVO;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Maps a TM Forum {@link AgreementVO} into the {@link BilateralContractVO} the consent-manager
 * expects from the facade's contract-service API.
 *
 * <p>The agreements read here are the ones the EDC TM Forum extension writes
 * ({@code TMFEdcMapper#toAgreement}): the provider/consumer are carried as engaged parties and as
 * {@code provider-id}/{@code consumer-id} characteristics, and the ODRL contract policy is carried
 * as the {@code policy} characteristic (see {@link AgreementCharacteristic}).
 *
 * <p>The mapping is hand-written: none of the {@link BilateralContractVO} fields align with an
 * {@link AgreementVO} field - they are derived from the agreement's characteristic and
 * engaged-party lists and from a JSON conversion of the ODRL policy - so a declarative mapper adds
 * no value over explicit code.
 *
 * <p>Scope note: per the current task this maps only into {@link BilateralContractVO}. The
 * catalog-graph fields whose values are facade URLs - {@code serviceOffering}, {@code purpose},
 * {@code uri}, {@code profile} - are intentionally left unset here; they are populated once the
 * catalog endpoints they must point at are wired in (see the consistency invariant in
 * {@code REQUIREMENTS.md} §6).
 */
@Slf4j
@Singleton
public class AgreementContractMapper {

    /** Contract signature status: signed by all parties (satisfies {@code hasSigned=true}). */
    private static final String STATUS_SIGNED = "signed";
    /** Contract signature status: not yet signed by all parties. */
    private static final String STATUS_PENDING = "pending";
    /** Contract signature status: consent/contract withdrawn. */
    private static final String STATUS_REVOKED = "revoked";
    /** Contract signature status: contract ended. */
    private static final String STATUS_TERMINATED = "terminated";
    /** Contract signature status: not yet a binding contract. */
    private static final String STATUS_DRAFT = "draft";

    /**
     * Mapping of known TM Forum agreement status values (lower-cased) to contract signature status.
     * Used only as a fallback when the agreement carries no {@code signing-date} characteristic.
     */
    private static final Map<String, String> AGREEMENT_STATUS_TO_CONTRACT_STATUS = Map.of(
            "approved", STATUS_SIGNED,
            "accepted", STATUS_SIGNED,
            "active", STATUS_SIGNED,
            "completed", STATUS_SIGNED,
            "rejected", STATUS_TERMINATED,
            "cancelled", STATUS_TERMINATED,
            "revoked", STATUS_REVOKED,
            "inprogress", STATUS_PENDING,
            "draft", STATUS_DRAFT);

    private final ObjectMapper objectMapper;

    /**
     * Creates the mapper.
     *
     * @param objectMapper the application Jackson mapper; a lenient copy is used to convert the
     *                      opaque ODRL policy characteristic into an {@link OdrlPolicyVO}
     */
    public AgreementContractMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * Maps an agreement into a bilateral contract.
     *
     * @param agreement the TM Forum agreement
     * @return the bilateral contract, or {@code null} if {@code agreement} is {@code null}
     */
    public BilateralContractVO toBilateralContract(AgreementVO agreement) {
        if (agreement == null) {
            return null;
        }
        return new BilateralContractVO()
                .id(agreement.getId())
                .uid(agreement.getId())
                .status(toContractStatus(agreement))
                .dataProvider(participantId(agreement, AgreementCharacteristic.PROVIDER_ID, EngagedPartyRole.PROVIDER))
                .dataConsumer(participantId(agreement, AgreementCharacteristic.CONSUMER_ID, EngagedPartyRole.CONSUMER))
                .policy(toPolicies(agreement))
                .createdAt(agreement.getInitialDate())
                .updatedAt(signingInstant(agreement).orElse(agreement.getInitialDate()));
    }

    /**
     * Whether a contract counts as signed by all parties, i.e. satisfies the consent-manager's
     * {@code hasSigned=true} filter.
     *
     * @param contract the contract
     * @return {@code true} if the contract's status is {@code signed}
     */
    public boolean isSigned(BilateralContractVO contract) {
        return STATUS_SIGNED.equals(contract.getStatus());
    }

    /**
     * Derives the contract signature status. An agreement carrying a {@code signing-date}
     * characteristic represents a concluded contract and maps to {@code signed}; otherwise the
     * agreement's own status is mapped via {@link #AGREEMENT_STATUS_TO_CONTRACT_STATUS}, defaulting
     * to {@code pending}.
     *
     * @param agreement the agreement
     * @return the contract status
     */
    private String toContractStatus(AgreementVO agreement) {
        if (TMForumBackedRepository.getCharacteristicValue(agreement, AgreementCharacteristic.SIGNING_DATE).isPresent()) {
            return STATUS_SIGNED;
        }
        return Optional.ofNullable(agreement.getStatus())
                .map(status -> AGREEMENT_STATUS_TO_CONTRACT_STATUS.get(status.toLowerCase()))
                .orElse(STATUS_PENDING);
    }

    /**
     * Resolves a participant's self-description identifier, preferring the dedicated
     * characteristic ({@code provider-id}/{@code consumer-id}) written by the EDC extension and
     * falling back to the id of the engaged party in the given role.
     */
    private String participantId(AgreementVO agreement, String characteristicName, String role) {
        return TMForumBackedRepository.getCharacteristicValue(agreement, characteristicName)
                .map(Object::toString)
                .orElseGet(() -> engagedPartyId(agreement, role).orElse(null));
    }

    private Optional<String> engagedPartyId(AgreementVO agreement, String role) {
        return Optional.ofNullable(agreement.getEngagedParty())
                .orElse(List.of())
                .stream()
                .filter(party -> Objects.equals(party.getRole(), role))
                .map(RelatedPartyVO::getId)
                .filter(Objects::nonNull)
                .findFirst();
    }

    /**
     * Converts the ODRL policy carried in the {@code policy} characteristic into the contract's
     * single-element policy list. Best-effort: an absent or unconvertible policy yields
     * {@code null}, so the contract simply carries no policy rather than failing the mapping.
     */
    private List<OdrlPolicyVO> toPolicies(AgreementVO agreement) {
        return TMForumBackedRepository.getCharacteristicValue(agreement, AgreementCharacteristic.POLICY)
                .flatMap(this::toPolicy)
                .map(List::of)
                .orElse(null);
    }

    private Optional<OdrlPolicyVO> toPolicy(Object rawPolicy) {
        try {
            return Optional.of(objectMapper.convertValue(rawPolicy, OdrlPolicyVO.class));
        } catch (IllegalArgumentException e) {
            log.debug("Could not convert the agreement policy characteristic into an ODRL policy.", e);
            return Optional.empty();
        }
    }

    /**
     * Reads the {@code signing-date} characteristic (epoch seconds, as written by the EDC
     * extension) as an {@link Instant}.
     */
    private Optional<Instant> signingInstant(AgreementVO agreement) {
        return TMForumBackedRepository.getCharacteristicValue(agreement, AgreementCharacteristic.SIGNING_DATE)
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(seconds -> Instant.ofEpochSecond(seconds.longValue()));
    }
}
