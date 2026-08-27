package org.fiware.consent.mapping;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.fiware.consent.model.BilateralContractVO;
import org.fiware.consent.model.OdrlPolicyVO;
import org.fiware.consent.model.OdrlRuleVO;
import org.fiware.consent.model.PurposeVO;
import org.fiware.consent.provider.ProviderScopedId;
import org.fiware.consent.tmforum.TMForumBackedRepository;
import org.fiware.consent.tmforum.TMForumBackedRepository.AgreementCharacteristic;
import org.fiware.consent.tmforum.TMForumBackedRepository.EngagedPartyRole;
import org.fiware.consent.tmforum.agreement.model.AgreementVO;
import org.fiware.consent.tmforum.agreement.model.RelatedPartyVO;

import java.time.Instant;
import java.util.ArrayList;
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
 * <p>The {@code serviceOffering} field is set to this facade's
 * {@code /catalog/serviceofferings/{agreementId}} URL (via {@link CatalogUrls}); dereferencing it
 * yields all of the agreement's product specifications as one {@code dataResources} bundle
 * (consented all-or-nothing). {@code purpose[]} points at the <em>same</em> offering URL (which also
 * exposes the specifications as {@code softwareResources}), and {@code profile} carries the agreement
 * id as the privacy-notice title.
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
    private final CatalogUrls catalogUrls;

    /**
     * Creates the mapper.
     *
     * @param objectMapper the application Jackson mapper; a lenient copy is used to convert the
     *                      opaque ODRL policy characteristic into an {@link OdrlPolicyVO}
     * @param catalogUrls  builds the {@code serviceOffering} URL the contract points back at
     */
    public AgreementContractMapper(ObjectMapper objectMapper, CatalogUrls catalogUrls) {
        this.objectMapper = objectMapper.copy()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.catalogUrls = catalogUrls;
    }

    /**
     * Maps an agreement into a bilateral contract, scoped to the provider whose TM Forum backend the
     * agreement was read from.
     *
     * <p>The {@code providerKey} is woven into the contract's {@code _id}/{@code uid} and into every
     * catalog URL the contract points at, so that when the consent-manager dereferences those URLs or
     * re-fetches the contract by id, the facade can route back to the same backend (multi-provider
     * plan, {@code REQUIREMENTS.md} §11.4).
     *
     * @param agreement   the TM Forum agreement
     * @param providerKey key of the provider owning the agreement
     * @return the bilateral contract, or {@code null} if {@code agreement} is {@code null}
     */
    public BilateralContractVO toBilateralContract(AgreementVO agreement, String providerKey) {
        if (agreement == null) {
            return null;
        }
        // The single service offering carries both the data (dataResources) and the purposes
        // (softwareResources); the contract points both serviceOffering and purpose[].purpose at it,
        // so the consent-manager can build a privacy notice with non-empty data AND purposes.
        String serviceOfferingUrl = catalogUrls.serviceOffering(providerKey, agreement.getId());
        String contractId = ProviderScopedId.of(providerKey, agreement.getId()).encode();
        return new BilateralContractVO()
                .id(contractId)
                .uid(contractId)
                .profile(agreement.getId())
                .status(toContractStatus(agreement))
                .dataProvider(participantId(agreement, AgreementCharacteristic.PROVIDER_ID, EngagedPartyRole.PROVIDER))
                .dataConsumer(participantId(agreement, AgreementCharacteristic.CONSUMER_ID, EngagedPartyRole.CONSUMER))
                .policy(toPolicies(agreement, serviceOfferingUrl))
                .serviceOffering(serviceOfferingUrl)
                .purpose(List.of(new PurposeVO().purpose(serviceOfferingUrl)))
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
     * single-element policy list, retargeting its rules to the contract's service-offering URL
     * (see {@link #retargetRules}). Best-effort: an absent or unconvertible policy yields
     * {@code null}, so the contract simply carries no policy rather than failing the mapping.
     */
    private List<OdrlPolicyVO> toPolicies(AgreementVO agreement, String serviceOfferingUrl) {
        return TMForumBackedRepository.getCharacteristicValue(agreement, AgreementCharacteristic.POLICY)
                .flatMap(this::toPolicy)
                .map(policy -> retargetRules(policy, serviceOfferingUrl))
                .map(List::of)
                .orElse(null);
    }

    /**
     * Normalizes and retargets the policy for the consent-manager. Two things it relies on:
     * <ul>
     *   <li>Both {@code permission} and {@code prohibition} exist as arrays - the consent-manager
     *       maps over each unconditionally, so a policy that omits one (and TM Forum drops empty
     *       arrays) would make it throw. Missing lists are defaulted to empty.</li>
     *   <li>A rule {@code target} is a service-offering URL - the consent-manager only reads a
     *       contract's data resources for rules whose target is <em>contained in</em> the contract's
     *       {@code serviceOffering} (a string-containment check, {@code REQUIREMENTS.md} §3.1). The
     *       EDC writes asset URNs, so every rule is retargeted to the one bundling offering
     *       (all-or-nothing, §5); without this the data chain never matches.</li>
     *   <li>The source target is <em>not</em> discarded: it is preserved verbatim in
     *       {@code assetTarget} before {@code target} is normalized. That asset URI is what
     *       identifies the concrete data object a rule governs, so a data-plane enforcer (e.g. the
     *       consent OwnerResolver) can match it against the requested resource. Consumers that do
     *       not know the field simply ignore it, so this stays backward compatible.</li>
     * </ul>
     */
    private OdrlPolicyVO retargetRules(OdrlPolicyVO policy, String serviceOfferingUrl) {
        if (policy.getPermission() == null) {
            policy.setPermission(new ArrayList<>());
        }
        if (policy.getProhibition() == null) {
            policy.setProhibition(new ArrayList<>());
        }
        retargetRules(policy.getPermission(), serviceOfferingUrl);
        retargetRules(policy.getProhibition(), serviceOfferingUrl);
        return policy;
    }

    private static void retargetRules(List<OdrlRuleVO> rules, String serviceOfferingUrl) {
        if (rules == null) {
            return;
        }
        rules.stream()
                .filter(Objects::nonNull)
                .forEach(rule -> {
                    preserveAssetTarget(rule);
                    rule.setTarget(serviceOfferingUrl);
                });
    }

    /**
     * Copies a rule's original {@code target} into {@code assetTarget} so the asset URI the source
     * agreement carried survives the normalization of {@code target} to the service-offering URL.
     * An already-set {@code assetTarget} wins (the source was explicit about it); a blank original
     * target leaves the field unset rather than storing an empty string.
     *
     * @param rule the rule about to be retargeted
     */
    private static void preserveAssetTarget(OdrlRuleVO rule) {
        if (rule.getAssetTarget() != null && !rule.getAssetTarget().isBlank()) {
            return;
        }
        String sourceTarget = rule.getTarget();
        if (sourceTarget != null && !sourceTarget.isBlank()) {
            rule.setAssetTarget(sourceTarget);
        }
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
