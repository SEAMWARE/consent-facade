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
package org.fiware.consent.tmforum;

import io.micronaut.http.uri.UriBuilder;

import java.nio.charset.StandardCharsets;

/**
 * The TM Forum v4 resource paths the facade reads, relative to a provider's base url.
 *
 * <p>These are the standard FIWARE {@code tm-forum-api} paths - the same ones the generated clients
 * use via {@code micronaut.http.services.*.path} (see {@code application.yaml}). For a non-default
 * provider only the base url varies (multi-provider plan, {@code REQUIREMENTS.md} §11.5); the API
 * paths are the TM Forum standard, so they live here as constants rather than per-provider config.
 *
 * <p>The ids interpolated here reach the facade as public path variables, so every dynamic segment is
 * percent-encoded ({@link #pathSegment(String)}) rather than concatenated. Concatenating them let a
 * caller inject query parameters ({@code spec?x=1}), truncate the path with a fragment
 * ({@code spec#frag}) or emit {@code ../} segments that a server or reverse proxy may normalise -
 * a request-injection primitive on the component whose job is to be the trusted boundary in front of
 * a provider's backend. Note that {@link UriBuilder#path(String)} alone does <em>not</em> encode
 * these characters.
 */
final class TMForumEndpoints {

    private static final String AGREEMENT_BASE = "/tmf-api/agreementManagement/v4";
    private static final String PARTY_BASE = "/tmf-api/party/v4";
    private static final String PRODUCT_CATALOG_BASE = "/tmf-api/productCatalogManagement/v4";
    private static final String PRODUCT_INVENTORY_BASE = "/tmf-api/productInventory/v4";

    private static final String AGREEMENT_RESOURCE = "/agreement/";
    private static final String ORGANIZATION_RESOURCE = "/organization/";
    private static final String PRODUCT_OFFERING_RESOURCE = "/productOffering/";
    private static final String PRODUCT_SPECIFICATION_RESOURCE = "/productSpecification/";
    private static final String PRODUCT_RESOURCE = "/product/";

    private static final String OFFSET_PARAM = "offset";
    private static final String LIMIT_PARAM = "limit";

    /**
     * Characters left as-is in a path segment: RFC 3986 {@code unreserved} plus {@code :} and
     * {@code @}, which {@code pchar} permits and the {@code urn:ngsi-ld:…} ids the FIWARE TM Forum
     * API generates are full of.
     */
    private static final String UNENCODED_SEGMENT_CHARACTERS = "-._~:@";

    /** The dot character, whose runs form the {@code .}/{@code ..} path-traversal segments. */
    private static final char DOT = '.';

    private static final String PERCENT_ENCODING_FORMAT = "%%%02X";

    private TMForumEndpoints() {
    }

    static String agreement(String id) {
        return AGREEMENT_BASE + AGREEMENT_RESOURCE + pathSegment(id);
    }

    static String agreements(int offset, int limit) {
        return page(AGREEMENT_BASE + "/agreement", offset, limit);
    }

    static String organization(String id) {
        return PARTY_BASE + ORGANIZATION_RESOURCE + pathSegment(id);
    }

    static String productOffering(String id) {
        return PRODUCT_CATALOG_BASE + PRODUCT_OFFERING_RESOURCE + pathSegment(id);
    }

    static String productSpecification(String id) {
        return PRODUCT_CATALOG_BASE + PRODUCT_SPECIFICATION_RESOURCE + pathSegment(id);
    }

    static String product(String id) {
        return PRODUCT_INVENTORY_BASE + PRODUCT_RESOURCE + pathSegment(id);
    }

    /**
     * Percent-encodes an id into a single path segment: anything outside
     * {@link #UNENCODED_SEGMENT_CHARACTERS} and the alphanumerics is escaped, so the id cannot leave
     * its segment or start a query or fragment. A segment made only of dots ({@code .}, {@code ..})
     * is escaped too, so it cannot be normalised into a traversal by a downstream proxy.
     *
     * @param id the raw id
     * @return the encoded path segment
     */
    static String pathSegment(String id) {
        boolean onlyDots = !id.isEmpty() && id.chars().allMatch(character -> character == DOT);
        StringBuilder encoded = new StringBuilder(id.length());
        for (byte rawByte : id.getBytes(StandardCharsets.UTF_8)) {
            char character = (char) (rawByte & 0xFF);
            if (isUnencoded(character) && !(onlyDots && character == DOT)) {
                encoded.append(character);
            } else {
                encoded.append(PERCENT_ENCODING_FORMAT.formatted(rawByte & 0xFF));
            }
        }
        return encoded.toString();
    }

    private static boolean isUnencoded(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9')
                || UNENCODED_SEGMENT_CHARACTERS.indexOf(character) >= 0;
    }

    private static String page(String path, int offset, int limit) {
        return UriBuilder.of(path)
                .queryParam(OFFSET_PARAM, offset)
                .queryParam(LIMIT_PARAM, limit)
                .build()
                .toString();
    }
}
