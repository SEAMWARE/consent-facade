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
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns a failed call to a provider's TM Forum backend into a {@code 502} towards the caller.
 *
 * <p>Covers every client-side failure: an error status from the backend
 * ({@link HttpClientResponseException}), a connection that could not be made, and a read timeout.
 *
 * <p>Without this, Micronaut's default handling of an {@link HttpClientResponseException} reflects the
 * downstream status and message outward: a provider backend's {@code 401}, {@code 403} or {@code 500},
 * and whatever its error body says, would surface on the consent-manager-facing API. That is both a
 * contract the API does not declare (see {@code api/consent-facade.yaml}) and a path for a provider's
 * internal backend to leak detail to an external consumer. A connection failure, meanwhile, is not the
 * facade's own fault and should not read as {@code 500}.
 *
 * <p>A {@code 404} from a backend never reaches here - the repositories complete empty on it, and the
 * controllers turn that into their own {@code 404}.
 */
@Produces
@Singleton
@Requires(classes = {HttpClientException.class, ExceptionHandler.class})
@Slf4j
public class TMForumClientExceptionHandler implements ExceptionHandler<HttpClientException, HttpResponse<?>> {

    /**
     * {@inheritDoc}
     *
     * @return a body-less {@code 502}; the downstream detail is logged instead of forwarded
     */
    @Override
    public HttpResponse<?> handle(HttpRequest request, HttpClientException exception) {
        if (exception instanceof HttpClientResponseException responseException) {
            log.error("A backend call for {} {} was answered with {}: {}", request.getMethodName(),
                    request.getPath(), responseException.getStatus(), exception.getMessage(), exception);
        } else {
            log.error("A backend call for {} {} could not be completed: {}", request.getMethodName(),
                    request.getPath(), exception.getMessage(), exception);
        }
        return HttpResponse.status(HttpStatus.BAD_GATEWAY);
    }
}
