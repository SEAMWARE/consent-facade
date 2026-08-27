package org.fiware.consent.exception;

/**
 * Thrown when an identifier arriving on a public path variable cannot be accepted, because using it
 * would let the caller shape the facade's outbound request rather than just name a resource.
 *
 * <p>Handled by {@link InvalidIdentifierExceptionHandler}, which answers {@code 400} with the reason.
 */
public class InvalidIdentifierException extends IllegalArgumentException {

    /**
     * @param message the reason the identifier was rejected; it reaches the caller, so it must not
     *                carry anything but the rule that was broken
     */
    public InvalidIdentifierException(String message) {
        super(message);
    }
}
