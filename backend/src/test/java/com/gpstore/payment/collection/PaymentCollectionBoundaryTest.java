package com.gpstore.payment.collection;

import com.gpstore.payment.gateway.CashfreeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §17: THE BOUNDARY, AND THE FLAG THAT REFUSES TO LIE.
 *
 * <p>GP-STORE runs one Cashfree account, so every shop's online money arrives
 * in the platform's account and is owed onward. That makes GP-STORE a payment
 * aggregator, which is a real position with real consequences - and §17 says
 * plainly not to "fix" it by inventing per-merchant gateway accounts on the
 * strength of an architectural preference. The decision is a business one.
 *
 * <p>WHAT THIS TESTS IS THEREFORE THE SHAPE, not a behaviour change. The
 * question "who collects for this shop" is now asked per shop, in code, with
 * one implemented answer - so the day the answer differs by shop, the change
 * is one method rather than a hunt for every place that assumed one account.
 *
 * <p>AND THE FLAG DOES NOT SILENTLY LIE. Configuring MERCHANT_COLLECTS
 * without an implementation stops the application from starting, because a
 * deployment that believes merchants are collecting while every rupee lands
 * in one account would report the wrong thing on every merchant's earnings
 * screen and in every settlement, and would do it quietly. A loud failure at
 * boot is the cheapest possible version of that discovery.
 */
@DisplayName("Who collects the money")
class PaymentCollectionBoundaryTest {

    private static CashfreeProperties gateway(String appId) {
        CashfreeProperties properties = new CashfreeProperties();
        properties.setAppId(appId);
        properties.setSecretKey("secret");
        return properties;
    }

    @Test
    @DisplayName("today, every shop's online money is collected by the platform")
    void theHonestAnswerToday() {
        PaymentCollection collection =
                new PaymentCollection("PLATFORM_COLLECTS", gateway("test-app"));

        PaymentCollection.Collector shopOne = collection.forShop(1L);
        PaymentCollection.Collector shopSeven = collection.forShop(7L);

        assertEquals(PaymentCollectionModel.PLATFORM_COLLECTS, shopOne.model());
        assertEquals(PaymentCollectionModel.PLATFORM_COLLECTS, shopSeven.model(),
                "every shop, not merely the first one");
        assertFalse(shopOne.merchantCollectsDirectly(),
                "THIS IS THE FACT THE PRODUCT HAS TO STOP HIDING. A merchant reading "
                        + "\"awaiting collection\" is entitled to know whose account it is "
                        + "awaiting collection FROM.");
        assertNotNull(shopOne.note(), "and to be told in words rather than an enum");
    }

    @Test
    @DisplayName("it is asked PER SHOP even while the answer is the same")
    void theSeamIsShaped() {
        // The signature is the thing being designed. A method without a shop
        // id would have to be found and changed everywhere the day the answer
        // differs by shop, which is exactly the migration this avoids - and
        // §17 asks for the boundary rather than the implementation.
        var forShop = java.util.Arrays.stream(PaymentCollection.class.getMethods())
                .filter(m -> m.getName().equals("forShop"))
                .findFirst().orElseThrow();
        assertEquals(Long.class, forShop.getParameterTypes()[0]);
    }

    @Test
    @DisplayName("asking for merchant collection stops the application, rather than pretending")
    void theFlagRefusesToLie() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new PaymentCollection("MERCHANT_COLLECTS", gateway("test-app")));

        assertTrue(refused.getMessage().contains("no per-merchant collection is implemented"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains("earnings"),
                "A HALF-IMPLEMENTED FLAG IS WORSE THAN NO FLAG. The message has to say what "
                        + "would have gone wrong, or the next person turns it on again.");
    }

    @Test
    @DisplayName("an unreadable setting is refused rather than guessed")
    void anUnknownModelIsRefused() {
        // Unlike PlatformProperties, which falls back to SINGLE_SHOP on a typo
        // because the safe answer is knowable. Here it is not: a deployment
        // that meant something about MONEY and mistyped it has two possible
        // intentions and picking one is guessing with somebody's takings.
        assertThrows(IllegalStateException.class,
                () -> new PaymentCollection("MERCHANT-COLLECTS", gateway("test-app")));
        assertThrows(IllegalStateException.class,
                () -> new PaymentCollection("split", gateway("test-app")));
    }

    @Test
    @DisplayName("a COD-only deployment has no account to name")
    void noGatewayConfigured() {
        PaymentCollection collection = new PaymentCollection("PLATFORM_COLLECTS", gateway(""));
        assertNull(collection.forShop(1L).accountRef(),
                "naming an account that is not configured would be inventing one");
        assertEquals(PaymentCollectionModel.PLATFORM_COLLECTS, collection.model());
    }
}
