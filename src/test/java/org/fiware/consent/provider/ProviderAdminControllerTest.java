package org.fiware.consent.provider;

import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProviderAdminController}: the runtime CRUD API over the persistent provider
 * registry (plan §11.8), backed by an in-memory H2. Each test uses a distinct provider key.
 */
@MicronautTest(transactional = false)
@Property(name = ProviderRegistry.PERSISTENT_PROPERTY, value = "true")
@Property(name = "datasources.default.url", value = "jdbc:h2:mem:provider-admin;DB_CLOSE_DELAY=-1")
@Property(name = "datasources.default.driver-class-name", value = "org.h2.Driver")
@Property(name = "datasources.default.username", value = "sa")
@Property(name = "datasources.default.dialect", value = "H2")
@Property(name = "datasources.default.schema-generate", value = "CREATE_DROP")
class ProviderAdminControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    private static ProviderRepresentation provider(String key, String tmforumBaseUrl) {
        return new ProviderRepresentation(key, tmforumBaseUrl, null);
    }

    private HttpStatus statusOf(HttpRequest<?> request) {
        return client.toBlocking().exchange(request).getStatus();
    }

    private HttpStatus errorStatusOf(HttpRequest<?> request) {
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(request));
        return exception.getStatus();
    }

    @Test
    void post_createsAProviderThenGetReturnsIt() {
        assertEquals(HttpStatus.CREATED,
                statusOf(HttpRequest.POST("/providers", provider("admin-create", "http://tmf.admin-create:8080"))),
                "Creating a provider returns 201.");

        ProviderRepresentation fetched = client.toBlocking()
                .retrieve(HttpRequest.GET("/providers/admin-create"), ProviderRepresentation.class);
        assertEquals("http://tmf.admin-create:8080", fetched.tmforumBaseUrl(), "The created provider is retrievable.");
    }

    @Test
    void post_isConflictForAnExistingProvider() {
        client.toBlocking().exchange(HttpRequest.POST("/providers", provider("admin-dup", "http://tmf:8080")));

        assertEquals(HttpStatus.CONFLICT,
                errorStatusOf(HttpRequest.POST("/providers", provider("admin-dup", "http://tmf:8080"))),
                "Re-creating an existing provider is a 409 conflict.");
    }

    @Test
    void post_isBadRequestForAKeyContainingTheIdSeparator() {
        assertEquals(HttpStatus.BAD_REQUEST,
                errorStatusOf(HttpRequest.POST("/providers", provider("bad~key", "http://tmf:8080"))),
                "A key containing '~' would make its ids un-decodable and is rejected.");
    }

    @Test
    void post_isBadRequestWithoutATmForumBaseUrl() {
        assertEquals(HttpStatus.BAD_REQUEST,
                errorStatusOf(HttpRequest.POST("/providers", provider("admin-nourl", null))),
                "A provider without a TM Forum base url is rejected.");
    }

    @Test
    void put_upsertsUnderThePathKey() {
        assertEquals(HttpStatus.OK,
                statusOf(HttpRequest.PUT("/providers/admin-put", provider("ignored-body-key", "http://tmf.put:8080"))),
                "PUT upserts and returns 200.");

        ProviderRepresentation fetched = client.toBlocking()
                .retrieve(HttpRequest.GET("/providers/admin-put"), ProviderRepresentation.class);
        assertEquals("admin-put", fetched.key(), "The path key wins over the body key.");
    }

    @Test
    void delete_removesAProvider() {
        client.toBlocking().exchange(HttpRequest.POST("/providers", provider("admin-del", "http://tmf:8080")));

        assertEquals(HttpStatus.NO_CONTENT, statusOf(HttpRequest.DELETE("/providers/admin-del")),
                "Deleting a provider returns 204.");
        assertEquals(HttpStatus.NOT_FOUND, errorStatusOf(HttpRequest.GET("/providers/admin-del")),
                "The deleted provider is gone.");
    }

    @Test
    void delete_isBadRequestForTheDefaultProvider() {
        assertEquals(HttpStatus.BAD_REQUEST,
                errorStatusOf(HttpRequest.DELETE("/providers/" + ProviderRegistry.DEFAULT_PROVIDER_KEY)),
                "The default provider is the routing fallback and cannot be deleted.");
    }

    @Test
    void get_isNotFoundForAnUnknownProvider() {
        assertEquals(HttpStatus.NOT_FOUND, errorStatusOf(HttpRequest.GET("/providers/admin-unknown")),
                "An unknown provider is a 404.");
    }

    @Test
    void list_includesTheSeededDefault() {
        ProviderRepresentation[] providers = client.toBlocking()
                .retrieve(HttpRequest.GET("/providers"), ProviderRepresentation[].class);

        assertTrue(java.util.Arrays.stream(providers)
                        .anyMatch(p -> p.key().equals(ProviderRegistry.DEFAULT_PROVIDER_KEY)),
                "Listing providers includes the seeded default.");
    }
}
