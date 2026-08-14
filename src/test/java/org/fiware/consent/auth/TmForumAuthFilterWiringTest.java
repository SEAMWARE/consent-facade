package org.fiware.consent.auth;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Inject;
import org.fiware.consent.tmforum.party.api.OrganizationApiClient;
import org.fiware.consent.tmforum.party.model.OrganizationVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link TmForumAuthClientFilter} is actually applied to the generated declarative TM
 * Forum clients (here the {@code party} client): with OID4VP enabled the filter intercepts the
 * client's request and routes it through the {@link AuthHandler}. A recording handler stands in for
 * the real OID4VP flow, and a fake party API answers the call.
 */
@MicronautTest
@Property(name = "oid4vp.enabled", value = "true")
@Property(name = "micronaut.server.port", value = "18099")
@Property(name = "micronaut.http.services.party.url", value = "http://localhost:18099")
class TmForumAuthFilterWiringTest {

    private static final AtomicInteger AUTH_HANDLER_CALLS = new AtomicInteger();

    @Inject
    OrganizationApiClient organizationApiClient;

    /** A recording {@link AuthHandler} that just proceeds — replaces the real OID4VP handler. */
    @MockBean(AuthHandler.class)
    AuthHandler recordingAuthHandler() {
        return (request, executor) -> {
            AUTH_HANDLER_CALLS.incrementAndGet();
            return executor.apply(request);
        };
    }

    @Test
    void filter_routesTheGeneratedPartyClientThroughTheAuthHandler() {
        AUTH_HANDLER_CALLS.set(0);

        List<OrganizationVO> organizations = organizationApiClient.listOrganization(null, 0, 100)
                .map(HttpResponse::body)
                .block();

        assertNotNull(organizations, "The fake party API responds through the filtered client.");
        assertTrue(AUTH_HANDLER_CALLS.get() > 0,
                "The auth filter intercepted the generated party client's outbound request.");
    }

    /** Fake TM Forum party API the filtered {@code party} client calls back into. */
    @Controller("/tmf-api/party/v4")
    static class FakePartyApi {
        @Get("/organization")
        List<OrganizationVO> organizations() {
            return List.of();
        }
    }
}
