package org.fiware.consent.auth;

/**
 * Thrown when a token is requested for an audience that is not configured in
 * {@link Oid4VpConfiguration#getTokenTargets()}.
 *
 * <p>This is a caller error, not a server fault: the set of audiences the facade will present the
 * participant's credential to is closed by configuration (ADR-0003).
 */
public class UnknownAudienceException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param audience the audience that is not configured
     */
    public UnknownAudienceException(String audience) {
        super("No token target is configured for audience '%s'.".formatted(audience));
    }
}
