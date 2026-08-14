package org.fiware.consent.auth;

import io.github.wistefan.oid4vp.OID4VPClient;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The unauthenticated fallback: with OID4VP disabled (the default), none of the auth beans exist, so
 * outbound TM Forum calls go out unauthenticated and the context starts normally.
 */
@MicronautTest
class Oid4VpFallbackTest {

    @Inject
    ApplicationContext context;

    @Test
    void disabled_noAuthBeansArePresent() {
        assertFalse(context.containsBean(AuthHandler.class), "No AuthHandler when oid4vp.enabled is not set.");
        assertFalse(context.containsBean(OID4VPClient.class), "No OID4VPClient when oid4vp.enabled is not set.");
    }
}
