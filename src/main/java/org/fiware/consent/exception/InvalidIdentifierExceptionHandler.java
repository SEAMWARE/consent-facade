package org.fiware.consent.exception;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Answers {@code 400} when a request carries an identifier the facade refuses to route on.
 *
 * <p>The rejection reason is safe to return: it names the rule that was broken, not anything about
 * the facade's internals or its backends.
 */
@Produces
@Singleton
@Requires(classes = {InvalidIdentifierException.class, ExceptionHandler.class})
@Slf4j
public class InvalidIdentifierExceptionHandler
        implements ExceptionHandler<InvalidIdentifierException, HttpResponse<?>> {

    /**
     * {@inheritDoc}
     *
     * @return {@code 400} carrying the rejection reason
     */
    @Override
    public HttpResponse<?> handle(HttpRequest request, InvalidIdentifierException exception) {
        log.debug("Rejected {} {}: {}", request.getMethodName(), request.getPath(), exception.getMessage());
        return HttpResponse.badRequest(exception.getMessage());
    }
}
