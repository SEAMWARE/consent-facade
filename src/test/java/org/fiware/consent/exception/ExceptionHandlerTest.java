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

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.exceptions.HttpClientException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the handlers that decide what a failure looks like from outside: a provider's backend must
 * never speak through the consent-manager-facing API, and an unmodelled failure must not carry the
 * facade's internals out with it.
 */
class ExceptionHandlerTest {

    private static final HttpRequest<?> REQUEST = HttpRequest.GET("/bilaterals/agr-1");

    @Test
    void aBackendErrorStatusBecomesABodylessBadGateway() {
        HttpResponse<?> response = new TMForumClientExceptionHandler().handle(REQUEST,
                new HttpClientResponseException("Unauthorized",
                        HttpResponse.unauthorized().body("the backend's own error detail")));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatus(),
                "the downstream status must not be reflected outward");
        assertTrue(response.getBody().isEmpty(), "and neither must the downstream body");
    }

    @Test
    void aBackendThatCannotBeReachedBecomesABadGatewayToo() {
        HttpResponse<?> response = new TMForumClientExceptionHandler().handle(REQUEST,
                new HttpClientException("Connect Error: Connection refused"));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatus(),
                "a transport failure is the backend's problem, not a 500 of ours");
    }

    @Test
    void anUnmodelledFailureBecomesABodylessInternalServerError() {
        HttpResponse<?> response = new UnhandledExceptionHandler().handle(REQUEST,
                new IllegalStateException("a stack trace's worth of internal detail"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatus());
        assertTrue(response.getBody().isEmpty(), "the detail is logged, not returned");
    }

    @Test
    void aRejectedIdentifierBecomesABadRequestCarryingTheRule() {
        HttpResponse<?> response = new InvalidIdentifierExceptionHandler().handle(REQUEST,
                new InvalidIdentifierException("An id must not contain '?'."));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
        assertEquals("An id must not contain '?'.", response.getBody().orElseThrow(),
                "the caller needs to know which rule they broke");
    }
}
