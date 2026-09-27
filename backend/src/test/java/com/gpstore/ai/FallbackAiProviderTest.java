package com.gpstore.ai;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackAiProviderTest {
    private final FallbackAiProvider provider = new FallbackAiProvider();

    @Test
    void biryaniRequestBecomesStructuredBasketConstraints() {
        MarketplaceAiProvider.Intent intent = provider.interpret(
                "I need groceries for biryani for 10 people under ₹1500").orElseThrow();

        assertThat(intent.budget()).isEqualByComparingTo(new BigDecimal("1500"));
        assertThat(intent.quantity()).isEqualTo(10);
        assertThat(intent.requiredItems()).contains(
                "basmati rice", "biryani masala", "curd", "cooking oil");
        assertThat(intent.language()).isEqualTo("ENGLISH");
    }

    @Test
    void hindiAndHinglishAreAcceptedWithoutInventingStock() {
        MarketplaceAiProvider.Intent intent = provider.interpret(
                "मुझे paneer butter masala के लिए सामान चाहिए").orElseThrow();

        assertThat(intent.language()).isEqualTo("HINGLISH");
        assertThat(intent.requiredItems()).contains("paneer", "butter", "cream");
        assertThat(intent.budget()).isNull();
    }

    @Test
    void repairIsConstrainedToServiceMode() {
        MarketplaceAiProvider.Intent intent =
                provider.interpret("Where can I repair my iPhone nearby?").orElseThrow();

        assertThat(intent.commerceMode()).isEqualTo("SERVICE_AT_SHOP");
        assertThat(intent.requiredItems()).isEmpty();
    }
}
