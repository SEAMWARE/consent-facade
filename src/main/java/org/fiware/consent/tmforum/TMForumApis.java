package org.fiware.consent.tmforum;

import org.fiware.consent.tmforum.agreement.model.AgreementVO;
import org.fiware.consent.tmforum.party.model.OrganizationVO;
import org.fiware.consent.tmforum.productcatalog.model.ProductOfferingVO;
import org.fiware.consent.tmforum.productcatalog.model.ProductSpecificationVO;
import org.fiware.consent.tmforum.productinventory.model.ProductVO;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The raw TM Forum read operations {@link TMForumBackedRepository} composes into its
 * domain-oriented lookups, decoupled from <em>how</em> the calls reach a TM Forum backend.
 *
 * <p>This seam is what makes the facade multi-provider (plan, {@code REQUIREMENTS.md} §11.5): the
 * business logic in {@link TMForumBackedRepository} is written once against this interface, while the
 * transport varies per provider. Two implementations exist:
 * <ul>
 *   <li>{@link GeneratedTMForumApis} - the default provider, over the compile-time
 *       {@code @Client(id=…)} beans bound to {@code micronaut.http.services.*};</li>
 *   <li>{@code HttpTMForumApis} - any other provider, over a low-level client bound to that
 *       provider's base url, produced at runtime by {@link org.fiware.consent.provider.TMForumClientFactory}.</li>
 * </ul>
 *
 * <p>All methods complete <em>empty</em> when the backend answers {@code 404}, so callers do not have
 * to distinguish "not found" from an error.
 */
public interface TMForumApis {

    /**
     * @param id the agreement id
     * @return the agreement, or empty on {@code 404}
     */
    Mono<AgreementVO> retrieveAgreement(String id);

    /**
     * @param offset index of the first agreement to return
     * @param limit  maximum number of agreements to return
     * @return the agreements in the requested page
     */
    Flux<AgreementVO> listAgreements(int offset, int limit);

    /**
     * @param id the organization id
     * @return the organization, or empty on {@code 404}
     */
    Mono<OrganizationVO> retrieveOrganization(String id);

    /**
     * @param offset index of the first organization to return
     * @param limit  maximum number of organizations to return
     * @return the organizations in the requested page
     */
    Flux<OrganizationVO> listOrganizations(int offset, int limit);

    /**
     * @param id the product-offering id
     * @return the product offering, or empty on {@code 404}
     */
    Mono<ProductOfferingVO> retrieveProductOffering(String id);

    /**
     * @param id the product-specification id
     * @return the product specification, or empty on {@code 404}
     */
    Mono<ProductSpecificationVO> retrieveProductSpecification(String id);

    /**
     * @param id the product id
     * @return the (inventory) product, or empty on {@code 404}
     */
    Mono<ProductVO> retrieveProduct(String id);
}
