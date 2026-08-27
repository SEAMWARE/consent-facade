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

import org.fiware.consent.tmforum.agreement.model.AgreementItemVO;
import org.fiware.consent.tmforum.agreement.model.AgreementVO;
import org.fiware.consent.tmforum.agreement.model.ProductOfferingRefVO;
import org.fiware.consent.tmforum.agreement.model.ProductRefVO;
import org.fiware.consent.tmforum.productcatalog.model.ProductOfferingVO;
import org.fiware.consent.tmforum.productcatalog.model.ProductSpecificationRefVO;
import org.fiware.consent.tmforum.productinventory.model.ProductVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TMForumBackedRepository#resolveSpecificationIds}: walking the native TM Forum
 * references from an agreement to the product specification(s) that back it, over a mocked
 * {@link TMForumApis}.
 */
class TMForumSpecificationResolutionTest {

    private TMForumApis apis;
    private TMForumBackedRepository repository;

    @BeforeEach
    void setUp() {
        apis = mock(TMForumApis.class);
        repository = new TMForumBackedRepository(apis);
    }

    private void stubOffering(String offeringId, String specificationId) {
        when(apis.retrieveProductOffering(eq(offeringId)))
                .thenReturn(Mono.just(new ProductOfferingVO().id(offeringId)
                        .productSpecification(new ProductSpecificationRefVO().id(specificationId))));
    }

    private void stubProduct(String productId, String specificationId) {
        when(apis.retrieveProduct(eq(productId)))
                .thenReturn(Mono.just(new ProductVO().id(productId)
                        .productSpecification(new org.fiware.consent.tmforum.productinventory.model.ProductSpecificationRefVO()
                                .id(specificationId))));
    }

    private static AgreementVO agreementReferencing(AgreementItemVO... items) {
        return new AgreementVO().agreementItem(List.of(items));
    }

    private Set<String> resolvedSpecificationIds(AgreementVO agreement) {
        return Set.copyOf(repository.resolveSpecificationIds(agreement).collectList().block());
    }

    @Test
    void resolveSpecificationIds_followsOfferingPath() {
        stubOffering("off-1", "spec-1");
        AgreementVO agreement = agreementReferencing(
                new AgreementItemVO().productOffering(List.of(new ProductOfferingRefVO().id("off-1"))));

        assertEquals(Set.of("spec-1"), resolvedSpecificationIds(agreement),
                "The offering path should resolve to its product specification.");
    }

    @Test
    void resolveSpecificationIds_followsProductPath() {
        stubProduct("prod-1", "spec-1");
        AgreementVO agreement = agreementReferencing(
                new AgreementItemVO().product(List.of(new ProductRefVO().id("prod-1"))));

        assertEquals(Set.of("spec-1"), resolvedSpecificationIds(agreement),
                "The product path should resolve to its product specification.");
    }

    @Test
    void resolveSpecificationIds_mergesBothPaths() {
        stubOffering("off-1", "spec-1");
        stubProduct("prod-1", "spec-2");
        AgreementVO agreement = agreementReferencing(new AgreementItemVO()
                .productOffering(List.of(new ProductOfferingRefVO().id("off-1")))
                .product(List.of(new ProductRefVO().id("prod-1"))));

        assertEquals(Set.of("spec-1", "spec-2"), resolvedSpecificationIds(agreement),
                "Both the offering and product paths should contribute their specifications.");
    }

    @Test
    void resolveSpecificationIds_deduplicatesSameSpecification() {
        stubOffering("off-1", "spec-1");
        stubProduct("prod-1", "spec-1");
        AgreementVO agreement = agreementReferencing(new AgreementItemVO()
                .productOffering(List.of(new ProductOfferingRefVO().id("off-1")))
                .product(List.of(new ProductRefVO().id("prod-1"))));

        List<String> specificationIds = repository.resolveSpecificationIds(agreement).collectList().block();
        assertEquals(1, specificationIds.size(),
                "An offering and a product pointing at the same specification resolve to it once.");
        assertEquals("spec-1", specificationIds.get(0), "The single resolved specification is spec-1.");
    }

    @Test
    void resolveSpecificationIds_isEmptyWhenAgreementReferencesNothing() {
        assertTrue(resolvedSpecificationIds(new AgreementVO()).isEmpty(),
                "An agreement without items resolves to no specifications.");
    }

    @Test
    void resolveSpecificationIds_isEmptyForNullAgreement() {
        assertTrue(repository.resolveSpecificationIds(null).collectList().block().isEmpty(),
                "A null agreement resolves to no specifications.");
    }
}
