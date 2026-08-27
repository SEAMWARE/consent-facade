/*
 * Copyright 2026 Seamless Middleware Technologies S.L and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
