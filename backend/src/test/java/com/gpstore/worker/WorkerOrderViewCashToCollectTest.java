package com.gpstore.worker;

import com.gpstore.entity.Order;
import com.gpstore.entity.Payment;
import com.gpstore.enums.OrderStatus;
import com.gpstore.enums.PaymentMethod;
import com.gpstore.enums.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How much cash the rider is told to take at the door.
 *
 * THE BUG THIS PINS, and it cost the shop money on every COD delivery:
 *
 *     PaymentMethod.COD.name().equals(payment.getPaymentMethod())
 *
 * The left side is the String "COD". getPaymentMethod() returns the
 * PaymentMethod ENUM. String.equals(Object) against an enum is always false -
 * it compiles, it reads correctly, and it can never be true. So `cod` was
 * always false, `amountToCollect` was always zero, and a rider handing over a
 * cash-on-delivery order was told to collect nothing.
 *
 * WorkerOrderView's own doc comment calls amountToCollect "the whole point"
 * of this view. It had never once worked.
 *
 * Three passes of manual audit read straight past it, because there is
 * nothing to see: the line looks exactly like a correct comparison. SpotBugs
 * flagged it as EC_UNRELATED_TYPES in seconds. That is the argument for
 * running a type-aware analyser rather than only reading code.
 */
@DisplayName("What the rider is told to collect")
class WorkerOrderViewCashToCollectTest {

    private static final BigDecimal TOTAL = new BigDecimal("450.00");

    private Order order() {
        Order o = new Order();
        o.setId(1L);
        o.setOrderNumber("GP-COD-1");
        o.setOrderStatus(OrderStatus.OUT_FOR_DELIVERY);
        o.setTotalAmount(TOTAL);
        return o;
    }

    private Payment payment(PaymentMethod method, PaymentStatus status) {
        Payment p = new Payment();
        p.setPaymentMethod(method);
        p.setPaymentStatus(status);
        p.setAmount(TOTAL);
        return p;
    }

    @Test
    @DisplayName("an unpaid cash order tells the rider the amount")
    void codUnsettledCollectsTheTotal() {
        WorkerOrderView view = WorkerOrderView.of(
                order(), null, payment(PaymentMethod.COD, PaymentStatus.PENDING), List.of());

        // THE FAILING CASE. Before the fix this was ZERO, so the rider handed
        // over the goods and asked for nothing.
        assertEquals(0, TOTAL.compareTo(view.amountToCollect()),
                "a rider was told to collect " + view.amountToCollect() + " on a COD order of " + TOTAL);
    }

    @Test
    @DisplayName("a prepaid order asks for nothing at the door")
    void prepaidCollectsNothing() {
        WorkerOrderView view = WorkerOrderView.of(
                order(), null, payment(PaymentMethod.ONLINE, PaymentStatus.SUCCESS), List.of());

        // The other half of the rule, and the reason this is not simply "show
        // the total": asking a customer who already paid online to pay again
        // at the door is the worse failure of the two.
        assertEquals(0, BigDecimal.ZERO.compareTo(view.amountToCollect()),
                "a prepaid customer was asked to pay again at the door");
    }

    @Test
    @DisplayName("a cash order already settled asks for nothing either")
    void codAlreadySettledCollectsNothing() {
        WorkerOrderView view = WorkerOrderView.of(
                order(), null, payment(PaymentMethod.COD, PaymentStatus.SUCCESS), List.of());

        assertEquals(0, BigDecimal.ZERO.compareTo(view.amountToCollect()),
                "cash already taken was asked for a second time");
    }

    @Test
    @DisplayName("an order with no payment row asks for nothing")
    void noPaymentCollectsNothing() {
        WorkerOrderView view = WorkerOrderView.of(order(), null, null, List.of());

        assertEquals(0, BigDecimal.ZERO.compareTo(view.amountToCollect()));
    }
}
