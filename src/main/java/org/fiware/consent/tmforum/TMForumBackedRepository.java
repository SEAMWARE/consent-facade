package org.fiware.consent.tmforum;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.consent.tmforum.agreement.api.AgreementApiClient;
import org.fiware.consent.tmforum.agreement.model.AgreementVO;
import org.fiware.consent.tmforum.agreement.model.CharacteristicVO;
import org.fiware.consent.tmforum.agreement.model.RelatedPartyVO;
import org.fiware.consent.tmforum.party.api.OrganizationApiClient;
import org.fiware.consent.tmforum.party.model.OrganizationVO;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Repository over the generated TM Forum HTTP clients, giving the facade a single,
 * domain-oriented access point to the two back-end APIs it reads from:
 *
 * <ul>
 *   <li>the <em>party-catalog</em> API (organizations - the participant self-descriptions), and</li>
 *   <li>the <em>agreement</em> API (the contracts, each carrying the ODRL contract policy as a
 *       {@code policy} characteristic).</li>
 * </ul>
 *
 * <p>The agreements this repository reads are the ones produced by the EDC TM Forum extension
 * ({@code org.seamware.edc.store.TMFEdcMapper#toAgreement}): the contract policy and the
 * negotiation metadata are carried as {@link CharacteristicVO} entries (see
 * {@link AgreementCharacteristic}) and the provider/consumer are carried as
 * {@link RelatedPartyVO engaged parties} (see {@link EngagedPartyRole}).
 *
 * <p>All methods are non-blocking and return Reactor types so they compose directly with the
 * reactive facade controllers. Lookups of a single resource complete <em>empty</em> when the
 * back end answers {@code 404}, rather than erroring.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor
public class TMForumBackedRepository {

    /**
     * Page size requested from the TM Forum list endpoints. The FIWARE tm-forum-api applies a
     * default page size when none is given; requesting an explicit, generous limit keeps the
     * repository's list results predictable. Callers needing full pagination should use the
     * offset/limit overloads.
     */
    private static final int DEFAULT_PAGE_LIMIT = 100;

    /** First page offset for the list endpoints. */
    private static final int FIRST_PAGE_OFFSET = 0;

    /**
     * {@code fields} query parameter passed to the TM Forum endpoints. {@code null} requests the
     * full representation (no field projection).
     */
    private static final String ALL_FIELDS = null;

    private final AgreementApiClient agreementApiClient;
    private final OrganizationApiClient organizationApiClient;

    // ---- agreements ------------------------------------------------------------------

    /**
     * Retrieves a single agreement by its TM Forum id.
     *
     * @param id the agreement id
     * @return the agreement, or an empty {@link Mono} if no agreement with that id exists
     */
    public Mono<AgreementVO> findAgreementById(String id) {
        return agreementApiClient.retrieveAgreement(id, ALL_FIELDS)
                .map(HttpResponse::body)
                .onErrorResume(TMForumBackedRepository::emptyOnNotFound);
    }

    /**
     * Lists the first page (see {@link #DEFAULT_PAGE_LIMIT}) of agreements.
     *
     * @return the agreements
     */
    public Flux<AgreementVO> findAgreements() {
        return findAgreements(FIRST_PAGE_OFFSET, DEFAULT_PAGE_LIMIT);
    }

    /**
     * Lists agreements with explicit pagination.
     *
     * @param offset index of the first agreement to return
     * @param limit  maximum number of agreements to return
     * @return the agreements in the requested page
     */
    public Flux<AgreementVO> findAgreements(int offset, int limit) {
        return agreementApiClient.listAgreement(ALL_FIELDS, offset, limit)
                .map(HttpResponse::body)
                .flatMapMany(TMForumBackedRepository::fluxFromNullable);
    }

    /**
     * Lists the agreements a party is engaged in, in either the provider or consumer role.
     *
     * <p>The TM Forum list endpoint exposed by the generated client does not support server-side
     * filtering by engaged party, so this filters the first page (see {@link #DEFAULT_PAGE_LIMIT})
     * client-side.
     *
     * @param partyId the {@link RelatedPartyVO#getId() id} of the engaged party
     * @return the agreements the party is engaged in
     */
    public Flux<AgreementVO> findAgreementsForParty(String partyId) {
        return findAgreements()
                .filter(agreement -> isEngagedParty(agreement, partyId));
    }

    // ---- organizations (participants) ------------------------------------------------

    /**
     * Retrieves a single organization by its TM Forum id.
     *
     * @param id the organization id
     * @return the organization, or an empty {@link Mono} if no organization with that id exists
     */
    public Mono<OrganizationVO> findOrganizationById(String id) {
        return organizationApiClient.retrieveOrganization(id, ALL_FIELDS)
                .map(HttpResponse::body)
                .onErrorResume(TMForumBackedRepository::emptyOnNotFound);
    }

    /**
     * Lists the first page (see {@link #DEFAULT_PAGE_LIMIT}) of organizations.
     *
     * @return the organizations
     */
    public Flux<OrganizationVO> findOrganizations() {
        return findOrganizations(FIRST_PAGE_OFFSET, DEFAULT_PAGE_LIMIT);
    }

    /**
     * Lists organizations with explicit pagination.
     *
     * @param offset index of the first organization to return
     * @param limit  maximum number of organizations to return
     * @return the organizations in the requested page
     */
    public Flux<OrganizationVO> findOrganizations(int offset, int limit) {
        return organizationApiClient.listOrganization(ALL_FIELDS, offset, limit)
                .map(HttpResponse::body)
                .flatMapMany(TMForumBackedRepository::fluxFromNullable);
    }

    // ---- agreement helpers -----------------------------------------------------------

    /**
     * Reads the value of a named characteristic from an agreement. The EDC TM Forum extension
     * carries the contract policy and the negotiation metadata as characteristics; use
     * {@link AgreementCharacteristic} for the well-known names.
     *
     * @param agreement          the agreement
     * @param characteristicName the characteristic name (e.g. {@link AgreementCharacteristic#POLICY})
     * @return the characteristic value, or empty if the agreement has no such characteristic
     */
    public static Optional<Object> getCharacteristicValue(AgreementVO agreement, String characteristicName) {
        return Optional.ofNullable(agreement.getCharacteristic())
                .orElse(List.of())
                .stream()
                .filter(characteristic -> Objects.equals(characteristic.getName(), characteristicName))
                .map(CharacteristicVO::getValue)
                .filter(Objects::nonNull)
                .findFirst();
    }

    private static boolean isEngagedParty(AgreementVO agreement, String partyId) {
        return Optional.ofNullable(agreement.getEngagedParty())
                .orElse(List.of())
                .stream()
                .anyMatch(engagedParty -> Objects.equals(engagedParty.getId(), partyId));
    }

    // ---- reactive plumbing -----------------------------------------------------------

    private static <T> Flux<T> fluxFromNullable(List<T> body) {
        return body == null ? Flux.empty() : Flux.fromIterable(body);
    }

    private static <T> Mono<T> emptyOnNotFound(Throwable throwable) {
        if (throwable instanceof HttpClientResponseException responseException
                && responseException.getStatus() == HttpStatus.NOT_FOUND) {
            return Mono.empty();
        }
        return Mono.error(throwable);
    }

    /**
     * Well-known agreement characteristic names, matching the keys written by the EDC TM Forum
     * extension ({@code TMFEdcMapper}). These form the read contract between the EDC store and
     * this facade.
     */
    public static final class AgreementCharacteristic {

        /** The ODRL contract policy of the agreement. */
        public static final String POLICY = "policy";
        /** Id of the asset the agreement is about. */
        public static final String ASSET_ID = "asset-id";
        /** Id of the providing participant. */
        public static final String PROVIDER_ID = "provider-id";
        /** Id of the consuming participant. */
        public static final String CONSUMER_ID = "consumer-id";
        /** Epoch-seconds timestamp at which the contract was signed. */
        public static final String SIGNING_DATE = "signing-date";

        private AgreementCharacteristic() {
        }
    }

    /**
     * Well-known engaged-party roles on an agreement, matching the roles written by the EDC
     * TM Forum extension ({@code ParticipantResolver}).
     */
    public static final class EngagedPartyRole {

        /** The party providing the data. */
        public static final String PROVIDER = "Provider";
        /** The party consuming the data. */
        public static final String CONSUMER = "Consumer";

        private EngagedPartyRole() {
        }
    }
}
