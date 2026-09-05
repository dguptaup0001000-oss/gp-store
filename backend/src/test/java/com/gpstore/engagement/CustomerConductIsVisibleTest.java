package com.gpstore.engagement;

import com.gpstore.dto.response.AdminCustomerDetailResponse;
import com.gpstore.entity.Customer;
import com.gpstore.entity.CustomerDeliveryRating;
import com.gpstore.entity.Role;
import com.gpstore.repository.CustomerDeliveryRatingRepository;
import com.gpstore.repository.CustomerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A rider's score for a customer has to reach the shopkeeper.
 *
 * THE FEATURE ONLY HAD A WRITE SIDE. Riders have been able to rate a delivery
 * out of ten since the collection screen shipped, and the rows have been
 * landing in customer_delivery_ratings ever since - but nothing anywhere read
 * them back. The whole reason the shop asked for the rating was to see how a
 * customer behaves at the door, and that was the half that did not exist.
 *
 * The rating is a judgement by one person on one day, so the count travels
 * with the average and "nobody has rated them" stays distinguishable from
 * "everybody rated them zero".
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("What riders think of a customer reaches the shop")
class CustomerConductIsVisibleTest {

    @Autowired private AdminCustomerDetailService detail;
    @Autowired private CustomerRepository customers;
    @Autowired private CustomerDeliveryRatingRepository ratings;

    private Customer newCustomer() {
        Customer customer = new Customer();
        customer.setFullName("Conduct Probe");
        customer.setEmail("conduct-" + UUID.randomUUID() + "@example.com");
        customer.setMobileNumber("9" + (100000000 + (int) (Math.random() * 899999999)));
        customer.setRole(Role.CUSTOMER);
        customer.setActive(true);
        customer.setEnabled(true);
        return customers.save(customer);
    }

    private void rate(Customer customer, long orderId, int score, LocalDateTime when) {
        CustomerDeliveryRating rating = new CustomerDeliveryRating();
        rating.setCustomerId(customer.getId());
        rating.setOrderId(orderId);
        rating.setPartnerId(3L);
        rating.setScore(score);
        rating.setCreatedAt(when);
        ratings.save(rating);
    }

    @Test
    @DisplayName("the average and the count both reach the customer file")
    void ratingsReachTheScreen() {
        Customer customer = newCustomer();
        rate(customer, 900001L, 9, LocalDateTime.now().minusDays(3));
        rate(customer, 900002L, 5, LocalDateTime.now().minusDays(2));

        AdminCustomerDetailResponse.DeliveryConduct conduct =
                detail.of(customer.getId()).conduct();

        assertNotNull(conduct, "the rider's score was written and never read back");
        assertEquals(2, conduct.ratedDeliveries());
        assertNotNull(conduct.averageScore());
        assertEquals(7.0, conduct.averageScore(), 0.001);
    }

    @Test
    @DisplayName("the newest rating is first, so a change of behaviour is visible")
    void newestFirst() {
        Customer customer = newCustomer();
        rate(customer, 900011L, 2, LocalDateTime.now().minusDays(30));
        rate(customer, 900012L, 10, LocalDateTime.now().minusDays(1));

        var recent = detail.of(customer.getId()).conduct().recent();

        assertEquals(2, recent.size());
        assertEquals(900012L, recent.get(0).orderId(),
                "a customer who was difficult once and fine since must not read as difficult");
        assertEquals(10, recent.get(0).score());
    }

    @Test
    @DisplayName("an unrated customer has no score - not a score of zero")
    void unratedIsNotZero() {
        // Zero is the worst possible rating. Rendering "we have never rated
        // this person" as zero would put a customer at the bottom of the list
        // for having done nothing at all.
        AdminCustomerDetailResponse.DeliveryConduct conduct =
                detail.of(newCustomer().getId()).conduct();

        assertNotNull(conduct);
        assertNull(conduct.averageScore(), "no ratings must read as unknown, never as zero");
        assertEquals(0, conduct.ratedDeliveries());
        assertTrue(conduct.recent().isEmpty());
    }

    @Test
    @DisplayName("the recent list is capped, the average is not")
    void theListIsCappedButTheAverageIsNot() {
        // A three-year regular has one of these per delivered order. The
        // screen shows the recent ones; an average over only those ten,
        // labelled as the customer's score, would be a different number
        // wearing the same name.
        Customer customer = newCustomer();
        for (int i = 0; i < 12; i++) {
            rate(customer, 900100L + i, 6, LocalDateTime.now().minusDays(12 - i));
        }

        AdminCustomerDetailResponse.DeliveryConduct conduct =
                detail.of(customer.getId()).conduct();

        assertEquals(10, conduct.recent().size(), "the list is capped at ten");
        assertEquals(12, conduct.ratedDeliveries(), "the count is over every rating");
        assertEquals(6.0, conduct.averageScore(), 0.001);
    }

    @Test
    @DisplayName("one customer's ratings never leak into another's file")
    void ratingsAreScopedToTheirCustomer() {
        Customer rated = newCustomer();
        Customer other = newCustomer();
        rate(rated, 900200L, 1, LocalDateTime.now());

        assertNull(detail.of(other.getId()).conduct().averageScore());
        assertEquals(0, detail.of(other.getId()).conduct().ratedDeliveries());
    }
}
