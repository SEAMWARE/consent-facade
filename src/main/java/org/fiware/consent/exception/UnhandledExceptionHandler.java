package org.fiware.consent.exception;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Last-resort handler: any failure the facade does not model becomes a body-less {@code 500}, and the
 * detail is logged server-side rather than returned.
 *
 * <p>Micronaut's more specific handlers (its own {@link io.micronaut.http.exceptions.HttpStatusException},
 * conversion and validation failures, and the handlers in this package) are still selected ahead of
 * this one - it only catches what nothing else claims.
 */
@Produces
@Singleton
@Requires(classes = ExceptionHandler.class)
@Slf4j
public class UnhandledExceptionHandler implements ExceptionHandler<RuntimeException, HttpResponse<?>> {

    /**
     * {@inheritDoc}
     *
     * @return a body-less {@code 500}
     */
    @Override
    public HttpResponse<?> handle(HttpRequest request, RuntimeException exception) {
        log.error("Unhandled failure while serving {} {}.", request.getMethodName(), request.getPath(), exception);
        return HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
