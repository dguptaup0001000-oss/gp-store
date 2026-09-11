package com.gpstore.payment.collection;

import com.gpstore.enums.PaymentProvider;
import com.gpstore.payment.gateway.PaymentGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE RULE IS DECIDED; THE IMPLEMENTATION BEHIND IT IS NOT.
 *
 * <p>A MERCHANT'S PRODUCT PROCEEDS BELONG TO THAT MERCHANT. GP-STORE is not to
 * operate a pooled account holding everybody's money and redistributing it
 * afterwards. That is a business requirement, not an architectural taste, and
 * this test exists so the code cannot quietly drift away from it or start
 * describing it as an open question again.
 *
 * <p>WHAT IS GENUINELY OPEN is how to honour it compliantly: the provider,
 * merchant onboarding and KYC, the settlement mechanism, how fees are borne,
 * how refunds are funded and reversed, and the regulatory position that
 * follows. §17 says build the boundary, do not invent what sits behind it - so
 * these tests check the SHAPE and the HONESTY of the seam, and assert nothing
 * about any provider.
 *
 * <p>TODAY'S ANSWER IS NON-CONFORMING AND SAYS SO. Every shop's online money
 * lands in GP-STORE's own account. The tests below pin that this is recorded
 * as an interim state rather than presented as the model, and that asking for
 * the target state without an implementation stops the application instead of
 * lying about where the money went.
 */
@DisplayName("Whose money it is")
class PaymentCollectionBoundaryTest {

    /**
     * A gateway that exists and names itself, without being any real one.
     *
     * <p>The seam must not know which provider it is talking to, so the test
     * must not hand it a real provider's configuration class either - that was
     * the shape of this test before, and it is why the boundary had a Cashfree
     * import in it.
     */
    private static PaymentGateway someGateway() {
        return new PaymentGateway() {
            @Override public PaymentProvider provider() { return PaymentProvider.CASHFREE; }
            @Override public GatewaySession createSession(GatewaySessionRequest request) {
                throw new UnsupportedOperationException("not used by this test");
            }
            @Override public GatewayOrderStatus fetchOrderStatus(String providerOrderId) {
                throw new UnsupportedOperationException("not used by this test");
            }
            @Override public GatewayRefund requestRefund(GatewayRefundRequest request) {
                throw new UnsupportedOperationException("not used by this test");
            }
            @Override public GatewayRefund fetchRefund(String providerOrderId, String refundId) {
                throw new UnsupportedOperationException("not used by this test");
            }
        };
    }

    /** The smallest ObjectProvider that answers "here is one" or "there is none". */
    private static ObjectProvider<PaymentGateway> provided(PaymentGateway gateway) {
        return new ObjectProvider<>() {
            @Override public PaymentGateway getObject() { return gateway; }
            @Override public PaymentGateway getObject(Object... args) { return gateway; }
            @Override public PaymentGateway getIfAvailable() { return gateway; }
            @Override public PaymentGateway getIfUnique() { return gateway; }
        };
    }

    private static PaymentCollection platformCollecting() {
        return new PaymentCollection("PLATFORM_COLLECTS", provided(someGateway()));
    }

    @Nested
    @DisplayName("the rule")
    class TheRule {

        @Test
        @DisplayName("only merchant collection satisfies it, and the code says which")
        void theRuleHasAnAnswer() {
            assertTrue(PaymentCollectionModel.MERCHANT_COLLECTS.meetsTheMerchantOwnsTheirMoneyRule(),
                    "a merchant receiving their own product money is the requirement");
            assertFalse(PaymentCollectionModel.PLATFORM_COLLECTS.meetsTheMerchantOwnsTheirMoneyRule(),
                    "one account holding every merchant's money is exactly what the rule "
                            + "forbids, so it must not report itself as satisfying it");
        }

        @Test
        @DisplayName("this deployment does not satisfy it, and admits it")
        void todayIsNonConforming() {
            PaymentCollection collection = platformCollecting();

            assertFalse(collection.meetsTheMerchantOwnsTheirMoneyRule(),
                    "GP-STORE collects every shop's money today. Reporting otherwise would "
                            + "be the application lying about where a merchant's money is.");
            assertEquals(PaymentCollectionModel.PLATFORM_COLLECTS, collection.model());
        }

        @Test
        @DisplayName("the requirement is written down, not left to be re-litigated")
        void theRequirementIsRecorded() throws IOException {
            String source = Files.readString(Path.of(
                    "src/main/java/com/gpstore/payment/collection/PaymentCollectionModel.java"));

            assertTrue(source.contains("belong to that merchant"),
                    "PaymentCollectionModel must state the decided rule. Without it, the next "
                            + "reader finds two enum values and assumes both are on the table.");
            assertTrue(source.contains("INTERIM AND NON-CONFORMING"),
                    "PLATFORM_COLLECTS must be labelled as the state to leave, not as one of "
                            + "two acceptable models.");
        }
    }

    @Nested
    @DisplayName("the seam")
    class TheSeam {

        @Test
        @DisplayName("it is asked PER SHOP even while the answer is the same")
        void theSeamIsShaped() {
            PaymentCollection collection = platformCollecting();

            PaymentCollection.Collector one = collection.forShop(1L);
            PaymentCollection.Collector two = collection.forShop(2L);

            assertEquals(one.model(), two.model(),
                    "every shop gets the same answer today - that is the honest state");
            assertFalse(one.merchantCollectsDirectly());
            assertNotNull(one.note(), "a merchant is told in words, not handed an enum");
        }

        @Test
        @DisplayName("nothing in the boundary names a payment provider")
        void theBoundaryIsProviderAgnostic() throws IOException {
            String source = Files.readString(Path.of(
                    "src/main/java/com/gpstore/payment/collection/PaymentCollection.java"));

            // The provider is asked for its own name; it is never assumed. A
            // provider class imported here would mean the seam had to be
            // reopened the day the provider changed, which is the one thing it
            // exists to prevent.
            assertFalse(source.contains("Cashfree"),
                    "PaymentCollection must not name a provider. It depends on the "
                            + "PaymentGateway interface and reads provider() for the name.");
            assertFalse(source.contains("Razorpay") || source.contains("razorpay"),
                    "nor any other provider");
        }

        @Test
        @DisplayName("the account reference says whose, never which")
        void theAccountReferenceIsOpaque() {
            PaymentCollection.Collector collector = platformCollecting().forShop(1L);

            assertEquals("platform", collector.accountRef(),
                    "an opaque 'whose account' reference. A real merchant or platform account "
                            + "id here would be inventing the settlement identity nobody has "
                            + "decided yet.");
            assertEquals("CASHFREE", collector.provider(),
                    "the provider's own name for itself, read from the gateway rather than "
                            + "hard-coded in the boundary");
        }

        @Test
        @DisplayName("a COD-only deployment has no account and no provider to name")
        void noGatewayConfigured() {
            PaymentCollection collection =
                    new PaymentCollection("PLATFORM_COLLECTS", provided(null));

            assertNull(collection.forShop(1L).accountRef(),
                    "naming an account that is not configured would be inventing one");
            assertNull(collection.forShop(1L).provider(),
                    "and there is no provider to name either");
            assertEquals(PaymentCollectionModel.PLATFORM_COLLECTS, collection.model());
        }
    }

    @Nested
    @DisplayName("the flag refuses to lie")
    class TheFlag {

        @Test
        @DisplayName("asking for merchant collection stops the application, rather than pretending")
        void theFlagRefusesToLie() {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> new PaymentCollection("MERCHANT_COLLECTS", provided(someGateway())));

            assertTrue(refused.getMessage().contains("no per-merchant collection is implemented"),
                    refused.getMessage());
            // The message has to distinguish the two things, or the next
            // person reads it as "the requirement is wrong" rather than "the
            // requirement is right and unbuilt".
            assertTrue(refused.getMessage().contains("requirement is right"),
                    "the refusal must say the rule is correct and the implementation missing, "
                            + "not imply the rule is in doubt: " + refused.getMessage());
        }

        @Test
        @DisplayName("an unreadable setting is refused rather than guessed")
        void anUnknownModelIsRefused() {
            assertThrows(IllegalStateException.class,
                    () -> new PaymentCollection("split", provided(someGateway())));
        }

        @Test
        @DisplayName("an absent setting means the honest default, not a crash")
        void blankIsTheInterimState() {
            PaymentCollection collection = new PaymentCollection("", provided(someGateway()));

            assertEquals(PaymentCollectionModel.PLATFORM_COLLECTS, collection.model(),
                    "a deployment that says nothing is doing what everything does today");
        }
    }
}
