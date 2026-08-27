package org.fiware.consent.configuration;

import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that {@link InternalApiPortFilter} really separates the two listeners: the internal API must
 * not be reachable through the public port, whatever the ingress in front of it allows.
 *
 * <p>Uses two fixed listener ports rather than {@code @MicronautTest}'s random one, because the whole
 * point is which port a request arrived on.
 */
class InternalApiPortFilterTest {

    private static final int PUBLIC_PORT = 18080;
    private static final int INTERNAL_PORT = 18090;

    /** A path on the internal API, and one on the public API, both of which exist in every profile. */
    private static final String INTERNAL_PATH = "/providers";
    private static final String PUBLIC_PATH = "/bilaterals/for/ZGlkOnByb3ZpZGVy";

    private static ApplicationContext context;
    private static HttpClient publicClient;
    private static HttpClient internalClient;

    @BeforeAll
    static void startServer() {
        context = ApplicationContext.run(Map.of(
                "micronaut.server.netty.listeners.public.port", PUBLIC_PORT,
                "micronaut.server.netty.listeners.internal.port", INTERNAL_PORT,
                InternalApiPortFilter.INTERNAL_PORT_PROPERTY, INTERNAL_PORT,
                "facade.providers.default.tmforum-base-url", "http://localhost:1",
                "facade.self-url", "http://localhost:" + PUBLIC_PORT), "test");
        context.getBean(EmbeddedServer.class).start();
        publicClient = context.createBean(HttpClient.class, "http://localhost:" + PUBLIC_PORT);
        internalClient = context.createBean(HttpClient.class, "http://localhost:" + INTERNAL_PORT);
    }

    @AfterAll
    static void stopServer() {
        if (context != null) {
            context.close();
        }
    }

    private static HttpStatus statusOf(BlockingHttpClient client, String path) {
        try {
            return client.exchange(HttpRequest.GET(path)).getStatus();
        } catch (HttpClientResponseException e) {
            return e.getStatus();
        }
    }

    @Test
    void theInternalApiIsNotReachableOnThePublicListener() {
        assertEquals(HttpStatus.NOT_FOUND, statusOf(publicClient.toBlocking(), INTERNAL_PATH),
                "the internal API must be refused on the public port, and refused as 'no such path' "
                        + "so its existence is not advertised there");
    }

    @Test
    void thePublicApiIsNotReachableOnTheInternalListener() {
        assertEquals(HttpStatus.NOT_FOUND, statusOf(internalClient.toBlocking(), PUBLIC_PATH),
                "the internal listener serves the internal API only");
    }

    @Test
    void thePublicApiIsStillReachableOnThePublicListener() {
        // The TM Forum backend is not running here, so the request reaches the controller and fails
        // there: a 502 proves the filter let it through (and that a transport failure is not a 500).
        assertEquals(HttpStatus.BAD_GATEWAY, statusOf(publicClient.toBlocking(), PUBLIC_PATH),
                "the public API must still be served on the public port");
    }
}
