package com.gpstore.ai;

import com.gpstore.catalog.shop.CommerceMode;
import com.gpstore.catalog.shop.ListingPriceMode;
import com.gpstore.platform.api.MarketplaceFeedService;
import com.gpstore.platform.api.MarketplaceFeedView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShoppingAssistantServiceTest {
    @Test
    void recommendationsContainOnlyDatabaseOffersAndRequireReview() {
        MarketplaceFeedService marketplace = mock(MarketplaceFeedService.class);
        MarketplaceFeedView realOffer = offer("Paneer", "120");
        when(marketplace.search(anyString(), eq(28.6), eq(77.2), anySet(), eq(0), eq(10)))
                .thenReturn(List.of(realOffer));
        ShoppingAssistantService service =
                new ShoppingAssistantService(new FallbackAiProvider(), marketplace, false);

        ShoppingAssistantService.Answer answer = service.answer(
                new ShoppingAssistantService.Request(
                        "ingredients for paneer butter masala under ₹1000", 28.6, 77.2));

        assertThat(answer.suggestedBasket()).isNotEmpty();
        assertThat(answer.suggestedBasket())
                .allSatisfy(line -> assertThat(line.offer()).isSameAs(realOffer));
        assertThat(answer.requiresCustomerReview()).isTrue();
        assertThat(answer.aiEnabled()).isFalse();
    }

    @Test
    void unrelatedDatabaseSearchHitsAreNotRecommended() {
        MarketplaceFeedService marketplace = mock(MarketplaceFeedService.class);
        MarketplaceFeedView unrelated = offer("TATA NIMAK", "30");
        MarketplaceFeedView relevant = offer("Biryani Masala", "85");
        when(marketplace.search(eq("masala"), eq(28.6), eq(77.2), anySet(), eq(0), eq(10)))
                .thenReturn(List.of(unrelated, relevant));
        ShoppingAssistantService service =
                new ShoppingAssistantService(new FallbackAiProvider(), marketplace, false);

        ShoppingAssistantService.Answer answer = service.answer(
                new ShoppingAssistantService.Request("masala", 28.6, 77.2));

        assertThat(answer.results()).containsExactly(relevant);
        assertThat(answer.suggestedBasket()).extracting(ShoppingAssistantService.BasketLine::offer)
                .containsExactly(relevant);
        assertThat(answer.unavailableItems()).isEmpty();
    }

    @Test
    void unrelatedSearchHitIsReportedAsUnavailableRatherThanPresentedAsAnOffer() {
        MarketplaceFeedService marketplace = mock(MarketplaceFeedService.class);
        when(marketplace.search(eq("masala"), anyDouble(), anyDouble(), anySet(), eq(0), eq(10)))
                .thenReturn(List.of(offer("TATA NIMAK", "30")));
        ShoppingAssistantService service =
                new ShoppingAssistantService(new FallbackAiProvider(), marketplace, false);

        ShoppingAssistantService.Answer answer = service.answer(
                new ShoppingAssistantService.Request("masala", 1.0, 2.0));

        assertThat(answer.suggestedBasket()).isEmpty();
        assertThat(answer.unavailableItems()).containsExactly("masala");
    }

    @Test
    void unavailableComponentsAreReportedInsteadOfHallucinated() {
        MarketplaceFeedService marketplace = mock(MarketplaceFeedService.class);
        when(marketplace.search(anyString(), anyDouble(), anyDouble(), anySet(), eq(0), eq(10)))
                .thenReturn(List.of());
        ShoppingAssistantService service =
                new ShoppingAssistantService(new FallbackAiProvider(), marketplace, false);

        ShoppingAssistantService.Answer answer = service.answer(
                new ShoppingAssistantService.Request("birthday cake and flowers", 1.0, 2.0));

        assertThat(answer.suggestedBasket()).isEmpty();
        assertThat(answer.unavailableItems()).containsExactly("birthday cake", "flowers");
    }

    private static MarketplaceFeedView offer(String name, String price) {
        return new MarketplaceFeedView(1L, name, null, 2L, "Food", null, true,
                3L, 1.0, "piece", new BigDecimal(price), new BigDecimal(price), null,
                ListingPriceMode.EXACT_PRICE, CommerceMode.ONLINE_PURCHASE, "Buy Online",
                null, null, 4L, "Real shop", 0.8, 1);
    }
}
