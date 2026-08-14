package org.fiware.consent.tmforum;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.fiware.consent.auth.AuthHandler;
import org.fiware.consent.auth.Oid4VpAuthHandler;
import org.fiware.consent.tmforum.agreement.model.AgreementVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link HttpTMForumApis}: that it issues the right TM Forum v4 request paths against its
 * base-url-bound client, maps a {@code 404} to an empty result and propagates other errors
 * ({@code REQUIREMENTS.md} §11.5), and — when an {@link AuthHandler} is supplied — routes the exchange
 * through it (implementation-plan.md path b).
 */
class HttpTMForumApisTest {

    private final HttpClient httpClient = mock(HttpClient.class);
    private final HttpTMForumApis apis = new HttpTMForumApis(httpClient, Optional.empty(), "", Set.of());

    private ArgumentCaptor<HttpRequest<?>> stubExchange(Object body) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<HttpRequest<?>> captor = ArgumentCaptor.forClass((Class) HttpRequest.class);
        when(httpClient.exchange(captor.capture(), any(Argument.class)))
                .thenReturn(Flux.just(HttpResponse.ok(body)));
        return captor;
    }

    @Test
    void retrieveAgreement_getsTheAgreementResourcePath() {
        ArgumentCaptor<HttpRequest<?>> request = stubExchange(new AgreementVO().id("agr-1"));

        AgreementVO agreement = apis.retrieveAgreement("agr-1").block();

        assertEquals("agr-1", agreement.getId(), "The deserialized agreement is returned.");
        assertEquals("/tmf-api/agreementManagement/v4/agreement/agr-1", request.getValue().getUri().getPath(),
                "The request targets the TM Forum v4 agreement resource path.");
    }

    @Test
    void listAgreements_getsThePagedCollectionPath() {
        ArgumentCaptor<HttpRequest<?>> request = stubExchange(List.of(new AgreementVO().id("agr-1")));

        List<AgreementVO> agreements = apis.listAgreements(0, 100).collectList().block();

        assertEquals(1, agreements.size(), "The list body is emitted element-wise.");
        assertEquals("/tmf-api/agreementManagement/v4/agreement", request.getValue().getUri().getPath(),
                "The request targets the TM Forum v4 agreement collection path.");
        assertEquals("offset=0&limit=100", request.getValue().getUri().getQuery(),
                "Pagination is passed as offset/limit query parameters.");
    }

    @Test
    void retrieveProductSpecification_getsTheSpecificationResourcePath() {
        ArgumentCaptor<HttpRequest<?>> request =
                stubExchange(new org.fiware.consent.tmforum.productcatalog.model.ProductSpecificationVO().id("spec-1"));

        apis.retrieveProductSpecification("spec-1").block();

        assertEquals("/tmf-api/productCatalogManagement/v4/productSpecification/spec-1",
                request.getValue().getUri().getPath(),
                "The request targets the TM Forum v4 product-specification resource path.");
    }

    @Test
    void retrieveAgreement_isEmptyOnNotFound() {
        when(httpClient.exchange(any(HttpRequest.class), any(Argument.class)))
                .thenReturn(Flux.error(new HttpClientResponseException("Not Found", HttpResponse.notFound())));

        assertTrue(apis.retrieveAgreement("missing").blockOptional().isEmpty(),
                "A 404 from the backend maps to an empty result.");
    }

    @Test
    void retrieveAgreement_propagatesNonNotFoundErrors() {
        when(httpClient.exchange(any(HttpRequest.class), any(Argument.class)))
                .thenReturn(Flux.error(new HttpClientResponseException("Boom", HttpResponse.serverError())));

        assertThrows(HttpClientResponseException.class, () -> apis.retrieveAgreement("agr-1").block(),
                "A non-404 error is propagated, not swallowed.");
    }

    @Test
    void retrieveAgreement_routesThroughTheAuthHandlerWhenPresent() {
        AtomicReference<HttpRequest<?>> interceptedRequest = new AtomicReference<>();
        AuthHandler authHandler = (request, executor) -> {
            interceptedRequest.set(request);
            return executor.apply(request);
        };
        HttpTMForumApis authenticatedApis =
                new HttpTMForumApis(httpClient, Optional.of(authHandler), "facade-client", Set.of("tmforum"));
        when(httpClient.exchange(any(HttpRequest.class), any(Argument.class)))
                .thenReturn(Flux.just(HttpResponse.ok(new AgreementVO().id("agr-1"))));

        AgreementVO agreement = authenticatedApis.retrieveAgreement("agr-1").block();

        assertEquals("agr-1", agreement.getId(), "The authenticated exchange returns the agreement.");
        assertNotNull(interceptedRequest.get(), "The auth handler intercepted the low-level request.");
        assertEquals("facade-client",
                interceptedRequest.get().getAttribute(Oid4VpAuthHandler.CLIENT_ID_ATTRIBUTE).orElse(null),
                "The provider's client_id is set for the auth handler.");
    }
}
