package org.fiware.consent.tmforum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests for {@link TMForumEndpoints}: the ids reaching these path helpers are public path variables,
 * so they must not be able to shape the outbound request beyond naming a resource.
 */
class TMForumEndpointsTest {

    static Stream<Arguments> pathSegments() {
        return Stream.of(
                // the ids the FIWARE TM Forum API actually generates pass through unchanged
                Arguments.of("spec-1", "spec-1"),
                Arguments.of("urn:ngsi-ld:product-specification:1", "urn:ngsi-ld:product-specification:1"),
                Arguments.of("a_b.c~d@e", "a_b.c~d@e"),
                // a query cannot be started, so no parameter can be injected into the backend call
                Arguments.of("spec?x=1", "spec%3Fx%3D1"),
                // the rest of the path cannot be dropped as a fragment
                Arguments.of("spec#frag", "spec%23frag"),
                // the segment cannot be left
                Arguments.of("a/b", "a%2Fb"),
                Arguments.of("a\\b", "a%5Cb"),
                // traversal segments cannot be normalised by a downstream proxy
                Arguments.of("..", "%2E%2E"),
                Arguments.of(".", "%2E"),
                // and a literal percent is escaped rather than starting an escape of the caller's own
                Arguments.of("%2F", "%252F"),
                Arguments.of("a b", "a%20b"),
                Arguments.of("*", "%2A"));
    }

    @ParameterizedTest
    @MethodSource("pathSegments")
    void pathSegment_encodesEverythingThatIsNotAPlainSegment(String id, String expected) {
        assertEquals(expected, TMForumEndpoints.pathSegment(id),
                "'" + id + "' must reach the backend as a single, inert path segment");
    }

    @Test
    void productSpecification_cannotBeMadeToCarryAQuery() {
        String path = TMForumEndpoints.productSpecification("spec?fields=*");

        assertFalse(path.contains("?"), "an injected query separator must not survive: " + path);
        assertEquals("/tmf-api/productCatalogManagement/v4/productSpecification/spec%3Ffields%3D%2A", path);
    }

    @Test
    void agreement_cannotBeMadeToWalkUpThePath() {
        String path = TMForumEndpoints.agreement("../../../../actuator/env");

        // The dots survive, but every separator is encoded, so they are text inside one segment rather
        // than the relative segments a server or proxy would normalise away.
        assertEquals("/tmf-api/agreementManagement/v4/agreement/..%2F..%2F..%2F..%2Factuator%2Fenv", path);
        assertEquals(5, path.chars().filter(character -> character == '/').count(),
                "no separator beyond the four the endpoint itself contributes: " + path);
        assertFalse(path.contains("/actuator"), "the caller must not reach another path: " + path);
    }

    @Test
    void agreements_carriesTheRequestedPage() {
        assertEquals("/tmf-api/agreementManagement/v4/agreement?offset=100&limit=100",
                TMForumEndpoints.agreements(100, 100));
    }
}
