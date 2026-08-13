package org.fiware.consent.tmforum;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import org.fiware.consent.tmforum.agreement.model.AgreementVO;
import org.fiware.consent.tmforum.party.model.OrganizationVO;
import org.fiware.consent.tmforum.productcatalog.model.ProductOfferingVO;
import org.fiware.consent.tmforum.productcatalog.model.ProductSpecificationVO;
import org.fiware.consent.tmforum.productinventory.model.ProductVO;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A {@link TMForumApis} over a low-level {@link HttpClient} bound to a single provider's TM Forum
 * base url.
 *
 * <p>This is what makes the facade serve a provider other than the default one (multi-provider plan,
 * {@code REQUIREMENTS.md} §11.5): the generated declarative clients are bound to a compile-time
 * service url and cannot be retargeted per request, so requests to another provider go through a
 * client the {@link org.fiware.consent.provider.TMForumClientFactory} created for that provider's
 * base url. Requests use relative TM Forum paths ({@link TMForumEndpoints}) that the base-url-bound
 * client resolves; responses deserialize into the same generated model classes the default path
 * uses. A {@code 404} completes empty.
 */
public class HttpTMForumApis implements TMForumApis {

    private final HttpClient httpClient;

    /**
     * @param httpClient a client bound to the provider's TM Forum base url (relative request paths
     *                   resolve against it)
     */
    public HttpTMForumApis(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public Mono<AgreementVO> retrieveAgreement(String id) {
        return get(TMForumEndpoints.agreement(id), AgreementVO.class);
    }

    @Override
    public Flux<AgreementVO> listAgreements(int offset, int limit) {
        return getList(TMForumEndpoints.agreements(offset, limit), AgreementVO.class);
    }

    @Override
    public Mono<OrganizationVO> retrieveOrganization(String id) {
        return get(TMForumEndpoints.organization(id), OrganizationVO.class);
    }

    @Override
    public Flux<OrganizationVO> listOrganizations(int offset, int limit) {
        return getList(TMForumEndpoints.organizations(offset, limit), OrganizationVO.class);
    }

    @Override
    public Mono<ProductOfferingVO> retrieveProductOffering(String id) {
        return get(TMForumEndpoints.productOffering(id), ProductOfferingVO.class);
    }

    @Override
    public Mono<ProductSpecificationVO> retrieveProductSpecification(String id) {
        return get(TMForumEndpoints.productSpecification(id), ProductSpecificationVO.class);
    }

    @Override
    public Mono<ProductVO> retrieveProduct(String id) {
        return get(TMForumEndpoints.product(id), ProductVO.class);
    }

    private <T> Mono<T> get(String path, Class<T> type) {
        return Mono.from(httpClient.retrieve(HttpRequest.GET(path), Argument.of(type)))
                .onErrorResume(TMForumResponses::emptyOnNotFound);
    }

    private <T> Flux<T> getList(String uri, Class<T> elementType) {
        return Mono.from(httpClient.retrieve(HttpRequest.GET(uri), Argument.listOf(elementType)))
                .onErrorResume(TMForumResponses::emptyOnNotFound)
                .flatMapMany(TMForumResponses::fluxFromNullable);
    }
}
