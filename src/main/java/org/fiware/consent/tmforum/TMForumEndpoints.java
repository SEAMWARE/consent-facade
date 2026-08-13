package org.fiware.consent.tmforum;

import io.micronaut.http.uri.UriBuilder;

/**
 * The TM Forum v4 resource paths the facade reads, relative to a provider's base url.
 *
 * <p>These are the standard FIWARE {@code tm-forum-api} paths - the same ones the generated clients
 * use via {@code micronaut.http.services.*.path} (see {@code application.yaml}). For a non-default
 * provider only the base url varies (multi-provider plan, {@code REQUIREMENTS.md} §11.5); the API
 * paths are the TM Forum standard, so they live here as constants rather than per-provider config.
 */
final class TMForumEndpoints {

    private static final String AGREEMENT_BASE = "/tmf-api/agreementManagement/v4";
    private static final String PARTY_BASE = "/tmf-api/party/v4";
    private static final String PRODUCT_CATALOG_BASE = "/tmf-api/productCatalogManagement/v4";
    private static final String PRODUCT_INVENTORY_BASE = "/tmf-api/productInventory/v4";

    private static final String OFFSET_PARAM = "offset";
    private static final String LIMIT_PARAM = "limit";

    private TMForumEndpoints() {
    }

    static String agreement(String id) {
        return AGREEMENT_BASE + "/agreement/" + id;
    }

    static String agreements(int offset, int limit) {
        return page(AGREEMENT_BASE + "/agreement", offset, limit);
    }

    static String organization(String id) {
        return PARTY_BASE + "/organization/" + id;
    }

    static String organizations(int offset, int limit) {
        return page(PARTY_BASE + "/organization", offset, limit);
    }

    static String productOffering(String id) {
        return PRODUCT_CATALOG_BASE + "/productOffering/" + id;
    }

    static String productSpecification(String id) {
        return PRODUCT_CATALOG_BASE + "/productSpecification/" + id;
    }

    static String product(String id) {
        return PRODUCT_INVENTORY_BASE + "/product/" + id;
    }

    private static String page(String path, int offset, int limit) {
        return UriBuilder.of(path)
                .queryParam(OFFSET_PARAM, offset)
                .queryParam(LIMIT_PARAM, limit)
                .build()
                .toString();
    }
}
