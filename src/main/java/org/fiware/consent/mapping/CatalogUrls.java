package org.fiware.consent.mapping;

import jakarta.inject.Singleton;
import org.fiware.consent.configuration.FacadeProperties;
import org.fiware.consent.provider.ProviderScopedId;

/**
 * Builds the catalog self-description URLs the facade both writes into contracts and serves back.
 *
 * <p>The consent-manager blindly dereferences the URLs a contract carries (its
 * {@code serviceOffering} and each {@code dataResources[]} entry), so the URL a contract points at
 * and the endpoint the facade answers must stay in lock-step (see {@code REQUIREMENTS.md} §6,
 * "consistency invariant"). Centralising their construction here is what keeps them aligned.
 *
 * <p>Each URL's last path segment is a {@link ProviderScopedId} ({@code providerKey~localId}), so the
 * provider that owns the resource travels with the URL and the facade can route the dereference back
 * to the right TM Forum backend (multi-provider plan, {@code REQUIREMENTS.md} §11.4).
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
     * @param providerKey        key of the provider owning the offering
     * @param serviceOfferingId  the backend-local service-offering id (an agreement id in this facade)
     * @return the absolute URL
     */
    public String serviceOffering(String providerKey, String serviceOfferingId) {
        return baseUrl + SERVICE_OFFERINGS_PATH + ProviderScopedId.of(providerKey, serviceOfferingId).encode();
    }

    /**
     * URL of a data-resource self-description.
     *
     * @param providerKey     key of the provider owning the data resource
     * @param dataResourceId  the backend-local data-resource id (a product-specification id in this facade)
     * @return the absolute URL
     */
    public String dataResource(String providerKey, String dataResourceId) {
        return baseUrl + DATA_RESOURCES_PATH + ProviderScopedId.of(providerKey, dataResourceId).encode();
    }

    /**
     * URL of a software-resource self-description.
     *
     * @param providerKey         key of the provider owning the software resource
     * @param softwareResourceId  the backend-local software-resource id
     * @return the absolute URL
     */
    public String softwareResource(String providerKey, String softwareResourceId) {
        return baseUrl + SOFTWARE_RESOURCES_PATH + ProviderScopedId.of(providerKey, softwareResourceId).encode();
    }

    private static String stripTrailingSeparator(String url) {
        if (url != null && url.endsWith(PATH_SEPARATOR)) {
            return url.substring(0, url.length() - PATH_SEPARATOR.length());
        }
        return url;
    }
}
