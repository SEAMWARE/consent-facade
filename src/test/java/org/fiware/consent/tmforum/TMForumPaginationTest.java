package org.fiware.consent.tmforum;

import org.fiware.consent.tmforum.agreement.model.AgreementVO;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that {@link TMForumBackedRepository#findAgreements()} walks every page.
 *
 * <p>This is the difference between "no contract exists" and "I did not look far enough": the facade
 * filters agreement lists client-side, so a listing that stopped at the first page reported a
 * participant party to agreement 120 of 150 as having no contracts at all - a silent, load-dependent
 * false negative that no demo would show.
 */
class TMForumPaginationTest {

    /** Matches {@code TMForumBackedRepository.DEFAULT_PAGE_LIMIT}; a shorter page ends the walk. */
    private static final int PAGE_LIMIT = 100;

    private final TMForumApis apis = mock(TMForumApis.class);
    private final TMForumBackedRepository repository = new TMForumBackedRepository(apis);

    /** A full page of agreements whose ids continue from {@code offset}. */
    private static Flux<AgreementVO> page(int offset, int size) {
        return Flux.fromIterable(IntStream.range(0, size)
                .mapToObj(index -> new AgreementVO().id("agreement-" + (offset + index)))
                .toList());
    }

    @Test
    void findAgreements_walksEveryPageUntilAShortOneEndsIt() {
        when(apis.listAgreements(eq(0), eq(PAGE_LIMIT))).thenReturn(page(0, PAGE_LIMIT));
        when(apis.listAgreements(eq(PAGE_LIMIT), eq(PAGE_LIMIT))).thenReturn(page(PAGE_LIMIT, 50));

        List<AgreementVO> agreements = repository.findAgreements().collectList().block();

        assertEquals(150, agreements.size(), "a participant's contract may sit on any page, not just the first");
        assertEquals("agreement-0", agreements.get(0).getId(), "pages are emitted in order");
        assertEquals("agreement-149", agreements.get(149).getId(), "the last page's entries are included");
        verify(apis, times(2)).listAgreements(anyInt(), eq(PAGE_LIMIT));
    }

    @Test
    void findAgreements_stopsAtASinglePageThatIsNotFull() {
        when(apis.listAgreements(eq(0), eq(PAGE_LIMIT))).thenReturn(page(0, 3));

        assertEquals(3, repository.findAgreements().collectList().block().size());
        verify(apis, times(1)).listAgreements(anyInt(), anyInt());
    }

    @Test
    void findAgreements_isEmptyWhenTheBackendHasNone() {
        when(apis.listAgreements(eq(0), eq(PAGE_LIMIT))).thenReturn(Flux.empty());

        assertTrue(repository.findAgreements().collectList().block().isEmpty());
        verify(apis, times(1)).listAgreements(anyInt(), anyInt());
    }

    @Test
    void findAgreements_isBoundedWhenTheBackendKeepsAnsweringFullPages() {
        when(apis.listAgreements(anyInt(), eq(PAGE_LIMIT)))
                .thenAnswer(invocation -> page(invocation.getArgument(0), PAGE_LIMIT));

        List<AgreementVO> agreements = repository.findAgreements().collectList().block();

        // MAX_PAGES pages of DEFAULT_PAGE_LIMIT: the walk terminates instead of running forever
        assertEquals(100 * PAGE_LIMIT, agreements.size(),
                "the page cap bounds the walk (and hitting it is logged as a truncated result)");
    }
}
