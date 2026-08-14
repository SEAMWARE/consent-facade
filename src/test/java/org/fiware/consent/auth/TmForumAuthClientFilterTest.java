package org.fiware.consent.auth;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.filter.ClientFilterChain;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TmForumAuthClientFilter}: it stamps the configured OID4VP {@code client_id}/scope
 * onto the request and delegates to the {@link AuthHandler}.
 */
class TmForumAuthClientFilterTest {

    @Test
    void doFilter_stampsClientIdAndScopeThenDelegatesToTheAuthHandler() {
        AuthHandler authHandler = mock(AuthHandler.class);
        when(authHandler.executeWithAuth(any(), any())).thenReturn(Mono.just(HttpResponse.ok()));

        Oid4VpConfiguration configuration = new Oid4VpConfiguration();
        configuration.setClientId("facade-client");
        configuration.setScopes(List.of("tmforum:read"));
        TmForumAuthClientFilter filter = new TmForumAuthClientFilter(authHandler, configuration);

        MutableHttpRequest<?> request = HttpRequest.GET("http://tmf.example/tmf-api/party/v4/organization");

        HttpResponse<?> response = Mono.from(filter.doFilter(request, mock(ClientFilterChain.class))).block();

        assertEquals(HttpStatus.OK, response.getStatus(), "The handler's response flows through the filter.");
        assertEquals("facade-client", request.getAttribute(Oid4VpAuthHandler.CLIENT_ID_ATTRIBUTE).orElse(null),
                "The configured client_id is set for the auth handler.");
        assertEquals(Set.of("tmforum:read"), request.getAttribute(Oid4VpAuthHandler.SCOPE_ATTRIBUTE, Set.class).orElse(null),
                "The configured scopes are set for the auth handler.");
        verify(authHandler).executeWithAuth(eq(request), any());
    }
}
