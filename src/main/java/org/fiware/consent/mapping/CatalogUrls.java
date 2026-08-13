package org.fiware.consent.mapping;

import jakarta.inject.Singleton;
import org.fiware.consent.configuration.FacadeProperties;

/**
 * Builds the catalog self-description URLs the facade both writes into contracts and serves back.
 *
 * <p>The consent-manager blindly dereferences the URLs a contract carries (its
 * {@code serviceOffering} and each {@code dataResources[]} entry), so the URL a contract points at
 * and the endpoint the facade answers must stay in lock-step (see {@code REQUIREMENTS.md} §6,
 * "consistency invariant"). Centralising their construction here is what keeps them aligned.
 */
@Singleton
public class CatalogUrls {

    private static final String SERVICE_OFFERINGS_PATH = "/catalog/serviceofferings/";
    private static final String DATA_RESOURCES_PATH = "/catalog/dataresources/";
    private static final String SOFTWARE_RESOURCES_PATH = "/catalog/softwareresources/";
    private static final String PATH_SEPARATOR = "/";

    private final String baseUrl;

    /**
     * @param facadeProperties provides the facade's public base url ({@code facade.self-url})
     */
    public CatalogUrls(FacadeProperties facadeProperties) {
        this.baseUrl = stripTrailingSeparator(facadeProperties.getSelfUrl());
    }

    /**
     * URL of a service-offering self-description ({@code dataResources}/{@code softwareResources}).
     *
     * @param serviceOfferingId the service-offering id (an agreement id in this facade)
     * @return the absolute URL
     */
    public String serviceOffering(String serviceOfferingId) {
        return baseUrl + SERVICE_OFFERINGS_PATH + serviceOfferingId;
    }

    /**
     * URL of a data-resource self-description.
     *
     * @param dataResourceId the data-resource id (a product-specification id in this facade)
     * @return the absolute URL
     */
    public String dataResource(String dataResourceId) {
        return baseUrl + DATA_RESOURCES_PATH + dataResourceId;
    }

    /**
     * URL of a software-resource self-description.
     *
     * @param softwareResourceId the software-resource id
     * @return the absolute URL
     */
    public String softwareResource(String softwareResourceId) {
        return baseUrl + SOFTWARE_RESOURCES_PATH + softwareResourceId;
    }

    private static String stripTrailingSeparator(String url) {
        if (url != null && url.endsWith(PATH_SEPARATOR)) {
            return url.substring(0, url.length() - PATH_SEPARATOR.length());
        }
        return url;
    }
}
