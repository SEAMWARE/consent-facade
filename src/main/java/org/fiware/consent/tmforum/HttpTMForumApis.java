package org.fiware.consent.tmforum;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.fiware.consent.auth.AuthHandler;
import org.fiware.consent.auth.Oid4VpAuthHandler;
import org.fiware.consent.tmforum.agreement.model.AgreementVO;
import org.fiware.consent.tmforum.party.model.OrganizationVO;
import org.fiware.consent.tmforum.productcatalog.model.ProductOfferingVO;
import org.fiware.consent.tmforum.productcatalog.model.ProductSpecificationVO;
import org.fiware.consent.tmforum.productinventory.model.ProductVO;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

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
 *
 * <p>When an {@link AuthHandler} is supplied (OID4VP enabled, implementation-plan.md path b), each
 * exchange is routed through it so the request is authenticated reactively on a {@code 401};
 * otherwise the exchange is issued directly (unauthenticated).
 */
public class HttpTMForumApis implements TMForumApis {

    private final HttpClient httpClient;
    private final Optional<AuthHandler> authHandler;
    private final String clientId;
    private final Set<String> scopes;

    /**
     * @param httpClient  a client bound to the provider's TM Forum base url (relative request paths
     *                    resolve against it)
     * @param authHandler the OID4VP auth handler, or empty for unauthenticated requests
     * @param clientId    the OID4VP {@code client_id} for this provider
     * @param scopes      the OID4VP scopes for this provider
     */
    public HttpTMForumApis(HttpClient httpClient, Optional<AuthHandler> authHandler, String clientId, Set<String> scopes) {
        this.httpClient = httpClient;
        this.authHandler = authHandler;
        this.clientId = clientId;
        this.scopes = scopes;
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
        Argument<T> bodyType = Argument.of(type);
        return exchange(HttpRequest.GET(path), bodyType)
                .flatMap(response -> body(response, bodyType))
                .onErrorResume(TMForumResponses::emptyOnNotFound);
    }

    private <T> Flux<T> getList(String uri, Class<T> elementType) {
        Argument<List<T>> listType = Argument.listOf(elementType);
        return exchange(HttpRequest.GET(uri), listType)
                .flatMap(response -> body(response, listType))
                .onErrorResume(TMForumResponses::emptyOnNotFound)
                .flatMapMany(TMForumResponses::fluxFromNullable);
    }

    /**
     * Issues the exchange, routed through the {@link AuthHandler} when present (which retries with a
     * bearer token on a {@code 401}). The client throws on error statuses; the handler turns those
     * into a response, so both the authenticated and unauthenticated paths surface a {@code 404} the
     * same way (see {@link #body}).
     */
    private Mono<HttpResponse> exchange(MutableHttpRequest<?> request, Argument<?> bodyType) {
        Function<MutableHttpRequest<?>, Mono<HttpResponse>> executor =
                proceededRequest -> Mono.from(httpClient.exchange(proceededRequest, bodyType))
                        .map(response -> (HttpResponse) response);
        return authHandler
                .map(handler -> {
                    request.setAttribute(Oid4VpAuthHandler.CLIENT_ID_ATTRIBUTE, clientId);
                    request.setAttribute(Oid4VpAuthHandler.SCOPE_ATTRIBUTE, scopes);
                    return handler.executeWithAuth(request, executor);
                })
                .orElseGet(() -> executor.apply(request));
    }

    /**
     * Extracts the body from a response: a {@code 404} completes empty, any other error status
     * propagates as an {@link HttpClientResponseException}, and a success yields the deserialized body.
     */
    private static <T> Mono<T> body(HttpResponse<?> response, Argument<T> bodyType) {
        HttpStatus status = response.getStatus();
        if (status == HttpStatus.NOT_FOUND) {
            return Mono.empty();
        }
        if (status.getCode() >= HttpStatus.BAD_REQUEST.getCode()) {
            return Mono.error(new HttpClientResponseException(status.getReason(), response));
        }
        return Mono.justOrEmpty(response.getBody(bodyType).orElse(null));
    }
}
