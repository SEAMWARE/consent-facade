package org.fiware.consent.tmforum;

import org.fiware.consent.tmforum.agreement.model.AgreementItemVO;
import org.fiware.consent.tmforum.agreement.model.AgreementVO;
import org.fiware.consent.tmforum.agreement.model.ProductOfferingRefVO;
import org.fiware.consent.tmforum.agreement.model.ProductRefVO;
import org.fiware.consent.tmforum.productcatalog.model.ProductOfferingVO;
import org.fiware.consent.tmforum.productcatalog.model.ProductSpecificationRefVO;
import org.fiware.consent.tmforum.productcatalog.model.ProductSpecificationVO;
import org.fiware.consent.tmforum.productinventory.model.ProductVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TMForumBackedRepository#resolveSpecifications}: walking the native TM Forum
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

    private void stubSpecification(String specificationId) {
        when(apis.retrieveProductSpecification(eq(specificationId)))
                .thenReturn(Mono.just(new ProductSpecificationVO().id(specificationId)));
    }

    private static AgreementVO agreementReferencing(AgreementItemVO... items) {
        return new AgreementVO().agreementItem(List.of(items));
    }

    private Set<String> resolvedSpecificationIds(AgreementVO agreement) {
        return repository.resolveSpecifications(agreement).collectList().block().stream()
                .map(ProductSpecificationVO::getId)
                .collect(Collectors.toSet());
    }

    @Test
    void resolveSpecifications_followsOfferingPath() {
        stubOffering("off-1", "spec-1");
        stubSpecification("spec-1");
        AgreementVO agreement = agreementReferencing(
                new AgreementItemVO().productOffering(List.of(new ProductOfferingRefVO().id("off-1"))));

        assertEquals(Set.of("spec-1"), resolvedSpecificationIds(agreement),
                "The offering path should resolve to its product specification.");
    }

    @Test
    void resolveSpecifications_followsProductPath() {
        stubProduct("prod-1", "spec-1");
        stubSpecification("spec-1");
        AgreementVO agreement = agreementReferencing(
                new AgreementItemVO().product(List.of(new ProductRefVO().id("prod-1"))));

        assertEquals(Set.of("spec-1"), resolvedSpecificationIds(agreement),
                "The product path should resolve to its product specification.");
    }

    @Test
    void resolveSpecifications_mergesBothPaths() {
        stubOffering("off-1", "spec-1");
        stubProduct("prod-1", "spec-2");
        stubSpecification("spec-1");
        stubSpecification("spec-2");
        AgreementVO agreement = agreementReferencing(new AgreementItemVO()
                .productOffering(List.of(new ProductOfferingRefVO().id("off-1")))
                .product(List.of(new ProductRefVO().id("prod-1"))));

        assertEquals(Set.of("spec-1", "spec-2"), resolvedSpecificationIds(agreement),
                "Both the offering and product paths should contribute their specifications.");
    }

    @Test
    void resolveSpecifications_deduplicatesSameSpecification() {
        stubOffering("off-1", "spec-1");
        stubProduct("prod-1", "spec-1");
        stubSpecification("spec-1");
        AgreementVO agreement = agreementReferencing(new AgreementItemVO()
                .productOffering(List.of(new ProductOfferingRefVO().id("off-1")))
                .product(List.of(new ProductRefVO().id("prod-1"))));

        List<ProductSpecificationVO> specifications = repository.resolveSpecifications(agreement).collectList().block();
        assertEquals(1, specifications.size(),
                "An offering and a product pointing at the same specification resolve to it once.");
        assertEquals("spec-1", specifications.get(0).getId(), "The single resolved specification is spec-1.");
    }

    @Test
    void resolveSpecifications_isEmptyWhenAgreementReferencesNothing() {
        assertTrue(resolvedSpecificationIds(new AgreementVO()).isEmpty(),
                "An agreement without items resolves to no specifications.");
    }

    @Test
    void resolveSpecifications_isEmptyForNullAgreement() {
        assertTrue(repository.resolveSpecifications(null).collectList().block().isEmpty(),
                "A null agreement resolves to no specifications.");
    }
}
