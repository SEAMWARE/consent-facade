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

import io.micronaut.http.HttpResponse;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import org.fiware.consent.tmforum.agreement.api.AgreementApiClient;
import org.fiware.consent.tmforum.agreement.model.AgreementVO;
import org.fiware.consent.tmforum.party.api.OrganizationApiClient;
import org.fiware.consent.tmforum.party.model.OrganizationVO;
import org.fiware.consent.tmforum.productcatalog.api.ProductOfferingApiClient;
import org.fiware.consent.tmforum.productcatalog.api.ProductSpecificationApiClient;
import org.fiware.consent.tmforum.productcatalog.model.ProductOfferingVO;
import org.fiware.consent.tmforum.productcatalog.model.ProductSpecificationVO;
import org.fiware.consent.tmforum.productinventory.api.ProductApiClient;
import org.fiware.consent.tmforum.productinventory.model.ProductVO;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The default provider's {@link TMForumApis}, backed by the generated declarative TM Forum clients
 * ({@code @Client(id=…)} beans whose base url comes from {@code micronaut.http.services.*}).
 *
 * <p>This is the bean injected wherever a {@link TMForumApis} is required, so the default provider
 * keeps using the compile-time clients exactly as before (multi-provider plan,
 * {@code REQUIREMENTS.md} §11.5). Other providers are served by
 * {@code HttpTMForumApis} instances the {@link org.fiware.consent.provider.TMForumClientFactory}
 * builds at runtime.
 */
@Singleton
@RequiredArgsConstructor
public class GeneratedTMForumApis implements TMForumApis {

    /**
     * {@code fields} query parameter passed to the TM Forum endpoints. {@code null} requests the
     * full representation (no field projection).
     */
    private static final String ALL_FIELDS = null;

    private final AgreementApiClient agreementApiClient;
    private final OrganizationApiClient organizationApiClient;
    private final ProductOfferingApiClient productOfferingApiClient;
    private final ProductSpecificationApiClient productSpecificationApiClient;
    private final ProductApiClient productApiClient;

    @Override
    public Mono<AgreementVO> retrieveAgreement(String id) {
        return agreementApiClient.retrieveAgreement(id, ALL_FIELDS)
                .mapNotNull(HttpResponse::body)
                .onErrorResume(TMForumResponses::emptyOnNotFound);
    }

    @Override
    public Flux<AgreementVO> listAgreements(int offset, int limit) {
        return agreementApiClient.listAgreement(ALL_FIELDS, offset, limit)
                .map(HttpResponse::body)
                .flatMapMany(TMForumResponses::fluxFromNullable);
    }

    @Override
    public Mono<OrganizationVO> retrieveOrganization(String id) {
        return organizationApiClient.retrieveOrganization(id, ALL_FIELDS)
                .mapNotNull(HttpResponse::body)
                .onErrorResume(TMForumResponses::emptyOnNotFound);
    }

    @Override
    public Mono<ProductOfferingVO> retrieveProductOffering(String id) {
        return productOfferingApiClient.retrieveProductOffering(id, ALL_FIELDS)
                .mapNotNull(HttpResponse::body)
                .onErrorResume(TMForumResponses::emptyOnNotFound);
    }

    @Override
    public Mono<ProductSpecificationVO> retrieveProductSpecification(String id) {
        return productSpecificationApiClient.retrieveProductSpecification(id, ALL_FIELDS)
                .mapNotNull(HttpResponse::body)
                .onErrorResume(TMForumResponses::emptyOnNotFound);
    }

    @Override
    public Mono<ProductVO> retrieveProduct(String id) {
        return productApiClient.retrieveProduct(id, ALL_FIELDS)
                .mapNotNull(HttpResponse::body)
                .onErrorResume(TMForumResponses::emptyOnNotFound);
    }
}
