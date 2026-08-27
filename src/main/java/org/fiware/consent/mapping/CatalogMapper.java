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
package org.fiware.consent.mapping;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.fiware.consent.configuration.FacadeProperties;
import org.fiware.consent.model.DataResourceVO;
import org.fiware.consent.model.ServiceOfferingVO;
import org.fiware.consent.model.SoftwareResourceVO;
import org.fiware.consent.provider.ProviderConfig;
import org.fiware.consent.provider.ProviderRegistry;
import org.fiware.consent.tmforum.productcatalog.model.CharacteristicValueSpecificationVO;
import org.fiware.consent.tmforum.productcatalog.model.ProductSpecificationCharacteristicVO;
import org.fiware.consent.tmforum.productcatalog.model.ProductSpecificationVO;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds the catalog self-descriptions the consent-manager dereferences from a contract:
 *
 * <ul>
 *   <li>a {@link ServiceOfferingVO} bundling <em>all</em> of an agreement's product specifications
 *       both as {@code dataResources} (the data) and as {@code softwareResources} (the purposes),</li>
 *   <li>a {@link DataResourceVO} per product specification, and</li>
 *   <li>a {@link SoftwareResourceVO} per product specification whose {@code name} is the processing
 *       purpose, read from the specification's {@code purpose} characteristic
 *       ({@code REQUIREMENTS.md} §0.2).</li>
 * </ul>
 *
 * <p>An agreement maps to a single service offering, so every specification the agreement resolves
 * to becomes one {@code dataResources} entry and one {@code softwareResources} entry - all
 * consented together (one contract, one privacy notice, one all-or-nothing {@code Consent}; see
 * {@code REQUIREMENTS.md} §5). Because the consent-manager reads {@code dataResources} off the
 * contract's {@code serviceOffering} URL and {@code softwareResources} off its {@code purpose[]}
 * URL - and this facade points both at the same offering - a single offering carries both.
 */
@Slf4j
@Singleton
public class CatalogMapper {

    /** {@code @type} of a service-offering self-description. */
    private static final String SERVICE_OFFERING_TYPE = "ServiceOffering";
    /** {@code @type} of a data-resource self-description. */
    private static final String DATA_RESOURCE_TYPE = "DataResource";
    /** {@code @type} of a software-resource self-description. */
    private static final String SOFTWARE_RESOURCE_TYPE = "SoftwareResource";

    /** Key of the purpose name within the {@code purpose} characteristic value (§0.2). */
    private static final String PURPOSE_NAME_KEY = "name";
    /** Key of the purpose description within the {@code purpose} characteristic value. */
    private static final String PURPOSE_DESCRIPTION_KEY = "description";
    /** All backing offerings require user interaction to grant consent (§0.2). */
    private static final Boolean REQUIRES_USER_INTERACTION = Boolean.TRUE;
    /** Consent-gated data is personal data by definition. */
    private static final Boolean CONTAINS_PII = Boolean.TRUE;

    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP = new TypeReference<>() {
    };

    private final CatalogUrls catalogUrls;
    private final FacadeProperties facadeProperties;
    private final ObjectMapper objectMapper;
    private final ProviderRegistry providerRegistry;

    /**
     * Creates the mapper.
     *
     * @param catalogUrls      builds the catalog self-description URLs (consistency invariant)
     * @param facadeProperties provides the purpose characteristic name and the legacy provider self-description
     * @param objectMapper     used to parse a JSON-string purpose characteristic value
     * @param providerRegistry resolves the routed provider's own self-description for {@code producedBy}
     */
    public CatalogMapper(CatalogUrls catalogUrls, FacadeProperties facadeProperties, ObjectMapper objectMapper,
                         ProviderRegistry providerRegistry) {
        this.catalogUrls = catalogUrls;
        this.facadeProperties = facadeProperties;
        this.objectMapper = objectMapper;
        this.providerRegistry = providerRegistry;
    }

    /**
     * Builds the service-offering self-description for an agreement, exposing every backing
     * specification both as a data-resource URL (the data) and as a software-resource URL (the
     * purpose), so the consent-manager can read {@code dataResources} off the contract's
     * {@code serviceOffering} and {@code softwareResources} off its {@code purpose[]}.
     *
     * @param providerKey       key of the provider owning the offering (woven into every URL)
     * @param serviceOfferingId the backend-local service-offering id (the agreement id)
     * @param specificationIds  the backend-local ids of the product specifications backing the agreement
     * @return the service offering
     */
    public ServiceOfferingVO toServiceOffering(String providerKey, String serviceOfferingId, List<String> specificationIds) {
        List<String> dataResourceUrls = specificationIds.stream()
                .map(specificationId -> catalogUrls.dataResource(providerKey, specificationId))
                .toList();
        List<String> softwareResourceUrls = specificationIds.stream()
                .map(specificationId -> catalogUrls.softwareResource(providerKey, specificationId))
                .toList();
        return new ServiceOfferingVO()
                .atId(catalogUrls.serviceOffering(providerKey, serviceOfferingId))
                .atType(SERVICE_OFFERING_TYPE)
                .dataResources(dataResourceUrls)
                .softwareResources(softwareResourceUrls)
                .userInteraction(REQUIRES_USER_INTERACTION);
    }

    /**
     * Maps a product specification into a data-resource self-description. {@code producedBy} is the
     * routed provider's own self-description ({@code facade.providers.<key>.self-description}, plan
     * §11.7) and {@code containsPII} is always {@code true} - both are required by the Prometheus-X
     * data-resource model. When the provider has no self-description configured, {@code producedBy}
     * falls back to the legacy global {@code facade.provider.self-description}.
     *
     * @param providerKey   key of the provider owning the specification (woven into the @id URL,
     *                      and whose self-description becomes {@code producedBy})
     * @param specification the product specification
     * @return the data resource, or {@code null} if {@code specification} is {@code null}
     */
    public DataResourceVO toDataResource(String providerKey, ProductSpecificationVO specification) {
        if (specification == null) {
            return null;
        }
        return new DataResourceVO()
                .atId(catalogUrls.dataResource(providerKey, specification.getId()))
                .atType(DATA_RESOURCE_TYPE)
                .name(specification.getName())
                .description(specification.getDescription())
                .producedBy(producedBy(providerKey))
                .containsPII(CONTAINS_PII);
    }

    /**
     * The {@code producedBy} of a data resource: the routed provider's own self-description, falling
     * back to the legacy global {@code facade.provider.self-description} when the provider has none.
     */
    private String producedBy(String providerKey) {
        return providerRegistry.byKey(providerKey)
                .map(ProviderConfig::selfDescription)
                .filter(selfDescription -> selfDescription != null && !selfDescription.isBlank())
                .orElseGet(() -> facadeProperties.getProvider().getSelfDescription());
    }

    /**
     * Maps a product specification into a software-resource self-description whose {@code name} is
     * the processing purpose. The purpose is read from the specification's configured
     * {@code purpose} characteristic (§0.2); when the specification carries none, the specification
     * name is used so the consent-manager still records a (non-null) purpose.
     *
     * @param providerKey   key of the provider owning the specification (woven into the @id URL)
     * @param specification the product specification
     * @return the software resource, or {@code null} if {@code specification} is {@code null}
     */
    public SoftwareResourceVO toSoftwareResource(String providerKey, ProductSpecificationVO specification) {
        if (specification == null) {
            return null;
        }
        Map<String, Object> purpose = readPurpose(specification);
        String purposeName = Optional.ofNullable(asString(purpose.get(PURPOSE_NAME_KEY)))
                .filter(name -> !name.isBlank())
                .orElseGet(specification::getName);
        return new SoftwareResourceVO()
                .atId(catalogUrls.softwareResource(providerKey, specification.getId()))
                .atType(SOFTWARE_RESOURCE_TYPE)
                .name(purposeName)
                .description(asString(purpose.get(PURPOSE_DESCRIPTION_KEY)));
    }

    /**
     * Reads the specification's purpose characteristic value as a map. The value may be a structured
     * object, a JSON string, or a plain string (taken as the purpose name); an absent characteristic
     * yields an empty map.
     */
    private Map<String, Object> readPurpose(ProductSpecificationVO specification) {
        Object value = firstPurposeValue(specification);
        if (value instanceof Map<?, ?> map) {
            return castToStringKeyedMap(map);
        }
        if (value instanceof String string && !string.isBlank()) {
            String trimmed = string.trim();
            if (trimmed.startsWith("{")) {
                try {
                    return objectMapper.readValue(trimmed, STRING_OBJECT_MAP);
                } catch (Exception e) {
                    log.debug("Purpose characteristic value is not valid JSON, using it as the purpose name.", e);
                }
            }
            return Map.of(PURPOSE_NAME_KEY, string);
        }
        return Map.of();
    }

    private Object firstPurposeValue(ProductSpecificationVO specification) {
        String purposeCharacteristic = facadeProperties.getSpec().getPurposeCharacteristic();
        return Optional.ofNullable(specification.getProductSpecCharacteristic())
                .orElse(List.of())
                .stream()
                .filter(characteristic -> Objects.equals(characteristic.getName(), purposeCharacteristic))
                .map(ProductSpecificationCharacteristicVO::getProductSpecCharacteristicValue)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(CharacteristicValueSpecificationVO::getValue)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static Map<String, Object> castToStringKeyedMap(Map<?, ?> map) {
        return map.entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .collect(java.util.stream.Collectors.toMap(entry -> entry.getKey().toString(), Map.Entry::getValue));
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
