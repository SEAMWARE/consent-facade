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
package org.fiware.consent.tmforum;

import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.consent.tmforum.agreement.model.AgreementItemVO;
import org.fiware.consent.tmforum.agreement.model.AgreementVO;
import org.fiware.consent.tmforum.agreement.model.CharacteristicVO;
import org.fiware.consent.tmforum.agreement.model.RelatedPartyVO;
import org.fiware.consent.tmforum.party.model.OrganizationVO;
import org.fiware.consent.tmforum.productcatalog.model.ProductOfferingVO;
import org.fiware.consent.tmforum.productcatalog.model.ProductSpecificationVO;
import org.fiware.consent.tmforum.productinventory.model.ProductVO;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * Repository giving the facade a single, domain-oriented access point to the TM Forum back-end
 * APIs it reads from:
 *
 * <ul>
 *   <li>the <em>party-catalog</em> API (organizations - the participant self-descriptions),</li>
 *   <li>the <em>agreement</em> API (the contracts, each carrying the ODRL contract policy as a
 *       {@code policy} characteristic),</li>
 *   <li>the <em>product-catalog</em> API (product offerings and their specifications), and</li>
 *   <li>the <em>product-inventory</em> API (the product instances materialized from an order).</li>
 * </ul>
 *
 * <p>The raw calls go through a {@link TMForumApis}, which decouples the business logic here from the
 * transport: the default provider's repository is backed by {@link GeneratedTMForumApis} (the
 * compile-time clients), while {@link org.fiware.consent.provider.TMForumClientFactory} builds a
 * repository over a low-level, base-url-bound {@link TMForumApis} for every other provider
 * (multi-provider plan, {@code REQUIREMENTS.md} §11.5).
 *
 * <p>Beyond simple lookups it offers {@link #resolveSpecificationIds(AgreementVO)}, which walks the
 * native TM Forum references from an agreement to the product specification(s) that back it - the
 * specification being the object that corresponds to a data resource.
 *
 * <p>The list methods walk <em>all</em> pages rather than returning the first one. The facade filters
 * agreement lists client-side (the TM Forum list endpoint has no server-side filter by engaged party),
 * so a truncated list is indistinguishable from "no such contract" for the caller: a participant party
 * to agreement 120 of 150 would be reported as having none. Paging is bounded by
 * {@link #MAX_PAGES}, and hitting that bound is logged rather than passed off as a complete answer.
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
     * default page size when none is given; requesting an explicit, generous limit keeps the number of
     * round trips down while walking all pages.
     */
    private static final int DEFAULT_PAGE_LIMIT = 100;

    /** First page offset for the list endpoints. */
    private static final int FIRST_PAGE_OFFSET = 0;

    /**
     * Maximum number of pages walked by a list method. A backstop against an endpoint that keeps
     * answering with full pages; reaching it is logged, because the result is then truncated.
     */
    private static final int MAX_PAGES = 100;

    /** Names used when logging that a listing was truncated. */
    private static final String AGREEMENTS = "agreements";

    private final TMForumApis apis;

    // ---- agreements ------------------------------------------------------------------

    /**
     * Retrieves a single agreement by its TM Forum id.
     *
     * @param id the agreement id
     * @return the agreement, or an empty {@link Mono} if no agreement with that id exists
     */
    public Mono<AgreementVO> findAgreementById(String id) {
        return apis.retrieveAgreement(id);
    }

    /**
     * Lists all agreements, walking the TM Forum list endpoint page by page (see
     * {@link #DEFAULT_PAGE_LIMIT}) until it is exhausted or {@link #MAX_PAGES} is reached.
     *
     * @return the agreements
     */
    public Flux<AgreementVO> findAgreements() {
        return allPages(offset -> findAgreements(offset, DEFAULT_PAGE_LIMIT), AGREEMENTS);
    }

    /**
     * Lists agreements with explicit pagination.
     *
     * @param offset index of the first agreement to return
     * @param limit  maximum number of agreements to return
     * @return the agreements in the requested page
     */
    public Flux<AgreementVO> findAgreements(int offset, int limit) {
        return apis.listAgreements(offset, limit);
    }

    // ---- organizations (participants) ------------------------------------------------

    /**
     * Retrieves a single organization by its TM Forum id.
     *
     * @param id the organization id
     * @return the organization, or an empty {@link Mono} if no organization with that id exists
     */
    public Mono<OrganizationVO> findOrganizationById(String id) {
        return apis.retrieveOrganization(id);
    }

    // ---- product catalog / inventory -------------------------------------------------

    /**
     * Retrieves a single product offering by its TM Forum id.
     *
     * @param id the product-offering id
     * @return the product offering, or an empty {@link Mono} if none with that id exists
     */
    public Mono<ProductOfferingVO> findProductOfferingById(String id) {
        return apis.retrieveProductOffering(id);
    }

    /**
     * Retrieves a single product specification by its TM Forum id.
     *
     * @param id the product-specification id
     * @return the product specification, or an empty {@link Mono} if none with that id exists
     */
    public Mono<ProductSpecificationVO> findProductSpecificationById(String id) {
        return apis.retrieveProductSpecification(id);
    }

    /**
     * Retrieves a single (inventory) product by its TM Forum id.
     *
     * @param id the product id
     * @return the product, or an empty {@link Mono} if none with that id exists
     */
    public Mono<ProductVO> findProductById(String id) {
        return apis.retrieveProduct(id);
    }

    /**
     * Resolves the ids of the product specification(s) an agreement is about, following the native
     * TM Forum references. An agreement's {@link AgreementItemVO agreement items} refer to product
     * offerings (the primary reference) and, optionally, to product instances; both carry a product
     * specification:
     *
     * <pre>
     *   agreement.agreementItem[].productOffering[] -&gt; ProductOffering.productSpecification -&gt; ProductSpecification
     *   agreement.agreementItem[].product[]         -&gt; Product.productSpecification          -&gt; ProductSpecification
     * </pre>
     *
     * <p>Both paths are followed and their results de-duplicated by specification id, so an agreement
     * referring to an offering, a product, or both resolves to the same specification once. The
     * specification is the object that corresponds to a data resource.
     *
     * @param agreement the agreement to resolve
     * @return the distinct product-specification ids backing the agreement (empty if it references none)
     */
    public Flux<String> resolveSpecificationIds(AgreementVO agreement) {
        if (agreement == null) {
            return Flux.empty();
        }
        List<AgreementItemVO> agreementItems = Optional.ofNullable(agreement.getAgreementItem()).orElse(List.of());

        Flux<String> specificationIdsFromOfferings = Flux.fromIterable(agreementItems)
                .flatMapIterable(item -> Optional.ofNullable(item.getProductOffering()).orElse(List.of()))
                .map(offeringRef -> offeringRef.getId())
                .filter(id -> id != null && !id.isBlank())
                .flatMap(this::findProductOfferingById)
                .map(ProductOfferingVO::getProductSpecification)
                .filter(Objects::nonNull)
                .map(specificationRef -> specificationRef.getId())
                .filter(id -> id != null && !id.isBlank());

        Flux<String> specificationIdsFromProducts = Flux.fromIterable(agreementItems)
                .flatMapIterable(item -> Optional.ofNullable(item.getProduct()).orElse(List.of()))
                .map(productRef -> productRef.getId())
                .filter(id -> id != null && !id.isBlank())
                .flatMap(this::findProductById)
                .map(ProductVO::getProductSpecification)
                .filter(Objects::nonNull)
                .map(specificationRef -> specificationRef.getId())
                .filter(id -> id != null && !id.isBlank());

        return Flux.merge(specificationIdsFromOfferings, specificationIdsFromProducts)
                .distinct();
    }

    // ---- pagination ------------------------------------------------------------------

    /**
     * Walks a paged TM Forum list endpoint to exhaustion. A page shorter than
     * {@link #DEFAULT_PAGE_LIMIT} is the last one; reaching {@link #MAX_PAGES} truncates the result and
     * is logged, because a caller filtering the result client-side cannot tell a truncated list from an
     * empty match.
     *
     * @param pageAt       fetches the page starting at the given offset
     * @param resourceName the resource being listed, for the truncation log
     * @param <T>          the listed type
     * @return every entry across all walked pages, in page order
     */
    private <T> Flux<T> allPages(IntFunction<Flux<T>> pageAt, String resourceName) {
        return fetchPage(pageAt, FIRST_PAGE_OFFSET)
                .expand(page -> nextPage(pageAt, page, resourceName))
                .flatMapIterable(Page::items);
    }

    private static <T> Mono<Page<T>> fetchPage(IntFunction<Flux<T>> pageAt, int offset) {
        return pageAt.apply(offset).collectList().map(items -> new Page<>(offset, items));
    }

    private static <T> Mono<Page<T>> nextPage(IntFunction<Flux<T>> pageAt, Page<T> page, String resourceName) {
        if (page.items().size() < DEFAULT_PAGE_LIMIT) {
            return Mono.empty();
        }
        int nextOffset = page.offset() + DEFAULT_PAGE_LIMIT;
        if (nextOffset >= MAX_PAGES * DEFAULT_PAGE_LIMIT) {
            log.warn("Stopped listing {} at {} entries: the {}-page cap was reached, so this result is "
                            + "truncated and a client-side filter over it may miss entries.",
                    resourceName, nextOffset, MAX_PAGES);
            return Mono.empty();
        }
        return fetchPage(pageAt, nextOffset);
    }

    /**
     * One page of a TM Forum listing.
     *
     * @param offset the offset the page was fetched at
     * @param items  the entries on it
     * @param <T>    the listed type
     */
    private record Page<T>(int offset, List<T> items) {
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
