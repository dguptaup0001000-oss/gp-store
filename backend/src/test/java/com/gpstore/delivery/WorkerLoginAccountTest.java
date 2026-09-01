package com.gpstore.delivery;

import com.gpstore.entity.Customer;
import com.gpstore.entity.DeliveryPartner;
import com.gpstore.entity.Role;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.dto.response.WorkerLoginAccountView;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.DeliveryPartnerRepository;
import com.gpstore.service.DeliveryPartnerService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Giving a rider a way into the worker app.
 *
 * THE BUG THIS CLOSES. Creating a delivery partner also created a login
 * account - found or made by MOBILE NUMBER, for OTP sign-in. That account has
 * no email and no password. But the worker app has no OTP form at all; email
 * and password is its only way in. So every partner the roster screen created
 * could be dispatched work they had no way to log in and collect, and a rider
 * typing their Gmail address into the worker app got
 *
 *   You don't have permission to do that.
 *
 * which is a 403 from /api/worker/me, because the account they typed was a
 * plain customer that no partner row pointed at. Nothing in the admin UI
 * could attach one, and update() never touched the link either, so a partner
 * created before this was unfixable.
 *
 * Real database and real repositories throughout: the association is LAZY,
 * the application runs with open-in-view=false, and the role promotion has to
 * survive an actual save - all three are things a mocked repository would
 * happily pretend about.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("A rider can be given an email login")
class WorkerLoginAccountTest {

    @Autowired
    private DeliveryPartnerService service;
    @Autowired
    private DeliveryPartnerRepository partners;
    @Autowired
    private CustomerRepository customers;
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Every row this class writes carries this prefix so cleanUp can find it.
     *
     * NOT OPTIONAL, and the suite proved why. The first version of this test
     * left its partners behind with available=true, active=true - which makes
     * them real auto-assignment candidates for every test that runs
     * afterwards. WorkerDeliveryStatusTest then failed in its own teardown,
     * because an order had been dispatched to a rider it had never heard of
     * and the resulting notification pinned a customer it was trying to
     * delete. The error named that test and had nothing to do with it.
     */
    private static final String MARKER = "WLAT-";

    @AfterEach
    void cleanUp() {
        // Unlink first: delivery_partners.account_customer_id is a foreign key
        // into the customers rows deleted on the last line.
        jdbc.update("UPDATE delivery_partners SET account_customer_id = NULL WHERE name LIKE ?",
                MARKER + "%");
        jdbc.update("DELETE FROM delivery_partners WHERE name LIKE ?", MARKER + "%");
        jdbc.update("DELETE FROM customers WHERE full_name LIKE ?", MARKER + "%");
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** A partner with NO login account, exactly as the roster screen leaves them. */
    private DeliveryPartner rosterOnlyPartner() {
        DeliveryPartner partner = new DeliveryPartner();
        partner.setName(MARKER + unique());
        partner.setMobile("9" + System.nanoTime() % 1000000000L);
        partner.setVehicleType("BIKE");
        // Unavailable and inactive on purpose: nothing here tests dispatch,
        // and an available fixture rider is a live auto-assignment candidate
        // for every other test sharing this database.
        partner.setAvailable(false);
        partner.setActive(false);
        // save() links a mobile account on create, so persist directly to get
        // the unlinked state that partners created before this feature have.
        return partners.save(partner);
    }

    private Customer shopper(String email, String password) {
        Customer customer = new Customer();
        customer.setFullName(MARKER + "Rider");
        customer.setEmail(email);
        customer.setMobileNumber("8" + System.nanoTime() % 1000000000L);
        customer.setPassword(password);
        customer.setRole(Role.CUSTOMER);
        customer.setEnabled(true);
        customer.setActive(true);
        customer.setVerified(true);
        return customers.save(customer);
    }

    @Test
    @DisplayName("linking an existing account promotes it to DELIVERY_BOY")
    void linkingPromotesTheAccount() {
        DeliveryPartner partner = rosterOnlyPartner();
        String email = "rider-" + unique() + "@gmail.com";
        Customer account = shopper(email, "$2a$10$hashedpasswordvaluegoeshere1234567890abcd");

        WorkerLoginAccountView view = service.linkLoginAccount(partner.getId(), email);

        assertTrue(view.linked());
        assertEquals(email, view.email());
        // The whole point: without this role the worker endpoints answer 403.
        assertEquals(Role.DELIVERY_BOY, customers.findById(account.getId()).orElseThrow().getRole());
        assertTrue(view.canSignIn(), "an account with an email and a password can use the worker form");
    }

    @Test
    @DisplayName("the partner is then reachable from the account, which is what /api/worker/me needs")
    void theLinkResolvesBackToThePartner() {
        DeliveryPartner partner = rosterOnlyPartner();
        String email = "rider-" + unique() + "@gmail.com";
        Customer account = shopper(email, "$2a$10$hashedpasswordvaluegoeshere1234567890abcd");

        service.linkLoginAccount(partner.getId(), email);

        // findByAccountId is what every worker endpoint resolves through.
        assertEquals(partner.getId(),
                partners.findByAccountId(account.getId()).orElseThrow().getId());
    }

    @Test
    @DisplayName("an unknown email is refused rather than silently creating an account")
    void unknownEmailIsRefused() {
        DeliveryPartner partner = rosterOnlyPartner();

        // An invented account would have no password, so it could not sign in
        // either - the same dead end one step further along.
        BadRequestException thrown = assertThrows(BadRequestException.class,
                () -> service.linkLoginAccount(partner.getId(), "nobody-" + unique() + "@gmail.com"));

        assertTrue(thrown.getMessage().contains("register in the customer app"),
                "the refusal must tell the admin what to do: " + thrown.getMessage());
    }

    @Test
    @DisplayName("an OTP-only account with no password is refused, with the reason")
    void passwordlessAccountIsRefused() {
        DeliveryPartner partner = rosterOnlyPartner();
        String email = "otponly-" + unique() + "@gmail.com";
        shopper(email, null);

        // This is precisely the account save() creates for a new partner.
        // Linking it would report success and leave the rider still locked
        // out, with nothing on screen explaining why.
        BadRequestException thrown = assertThrows(BadRequestException.class,
                () -> service.linkLoginAccount(partner.getId(), email));

        assertTrue(thrown.getMessage().contains("password"), thrown.getMessage());
    }

    @Test
    @DisplayName("one account cannot be the login for two riders")
    void oneAccountCannotServeTwoPartners() {
        DeliveryPartner first = rosterOnlyPartner();
        DeliveryPartner second = rosterOnlyPartner();
        String email = "shared-" + unique() + "@gmail.com";
        shopper(email, "$2a$10$hashedpasswordvaluegoeshere1234567890abcd");

        service.linkLoginAccount(first.getId(), email);

        // findByAccountId returns Optional, so a second link would throw a
        // non-unique-result error on the rider's very next request - far from
        // the admin who caused it.
        assertThrows(ConflictException.class,
                () -> service.linkLoginAccount(second.getId(), email));
    }

    @Test
    @DisplayName("re-linking the same account to the same rider is not a conflict")
    void relinkingTheSamePairIsIdempotent() {
        DeliveryPartner partner = rosterOnlyPartner();
        String email = "again-" + unique() + "@gmail.com";
        shopper(email, "$2a$10$hashedpasswordvaluegoeshere1234567890abcd");

        service.linkLoginAccount(partner.getId(), email);
        assertDoesNotThrow(() -> service.linkLoginAccount(partner.getId(), email));
    }

    @Test
    @DisplayName("an ADMIN handed a delivery round is never downgraded")
    void adminIsNeverDemoted() {
        DeliveryPartner partner = rosterOnlyPartner();
        String email = "owner-" + unique() + "@gmail.com";
        Customer admin = shopper(email, "$2a$10$hashedpasswordvaluegoeshere1234567890abcd");
        admin.setRole(Role.ADMIN);
        customers.save(admin);

        service.linkLoginAccount(partner.getId(), email);

        // ADMIN already implies every delivery permission; overwriting it with
        // DELIVERY_BOY would strip the shop owner of their own console.
        assertEquals(Role.ADMIN, customers.findById(admin.getId()).orElseThrow().getRole());
    }

    @Test
    @DisplayName("email matching ignores case, because people type their address how they like")
    void emailMatchIsCaseInsensitive() {
        DeliveryPartner partner = rosterOnlyPartner();
        String email = "mixed-" + unique() + "@gmail.com";
        shopper(email, "$2a$10$hashedpasswordvaluegoeshere1234567890abcd");

        assertDoesNotThrow(
                () -> service.linkLoginAccount(partner.getId(), "  " + email.toUpperCase() + "  "));
    }

    @Test
    @DisplayName("unlinking demotes DELIVERY_BOY back to CUSTOMER")
    void unlinkingDemotesTheRiderRole() {
        DeliveryPartner partner = rosterOnlyPartner();
        String email = "leaver-" + unique() + "@gmail.com";
        Customer account = shopper(email, "$2a$10$hashedpasswordvaluegoeshere1234567890abcd");

        service.linkLoginAccount(partner.getId(), email);
        WorkerLoginAccountView after = service.unlinkLoginAccount(partner.getId());

        assertFalse(after.linked());
        // Left as DELIVERY_BOY, the account still reaches the worker endpoints
        // and gets "no delivery partner profile" from every one of them.
        assertEquals(Role.CUSTOMER, customers.findById(account.getId()).orElseThrow().getRole());
        assertTrue(partners.findByAccountId(account.getId()).isEmpty());
    }

    @Test
    @DisplayName("unlinking never changes an ADMIN's role")
    void unlinkingLeavesOtherRolesAlone() {
        DeliveryPartner partner = rosterOnlyPartner();
        String email = "boss-" + unique() + "@gmail.com";
        Customer admin = shopper(email, "$2a$10$hashedpasswordvaluegoeshere1234567890abcd");
        admin.setRole(Role.ADMIN);
        customers.save(admin);

        service.linkLoginAccount(partner.getId(), email);
        service.unlinkLoginAccount(partner.getId());

        // Unlinking a rider is not a place to change what an admin is.
        assertEquals(Role.ADMIN, customers.findById(admin.getId()).orElseThrow().getRole());
    }

    @Test
    @DisplayName("reading the link works with a lazy association and open-in-view off")
    void readingTheLinkDoesNotNeedAnOpenView() {
        DeliveryPartner partner = rosterOnlyPartner();
        String email = "lazy-" + unique() + "@gmail.com";
        shopper(email, "$2a$10$hashedpasswordvaluegoeshere1234567890abcd");
        service.linkLoginAccount(partner.getId(), email);

        // DeliveryPartner.account is LAZY and the app runs open-in-view=false,
        // so reading the email outside a transaction would throw. The service
        // returns a DTO built inside one, which is what makes this safe.
        WorkerLoginAccountView view = service.getLoginAccount(partner.getId());
        assertEquals(email, view.email());
    }

    @Test
    @DisplayName("an unlinked rider reports no account rather than failing")
    void unlinkedRiderReportsNoAccount() {
        WorkerLoginAccountView view = service.getLoginAccount(rosterOnlyPartner().getId());

        assertFalse(view.linked());
        assertNull(view.email());
        assertFalse(view.canSignIn());
    }

    @Test
    @DisplayName("a missing rider is a 404, not a silent no-op")
    void missingPartnerIsNotFound() {
        assertThrows(ResourceNotFoundException.class,
                () -> service.linkLoginAccount(987654321L, "someone@gmail.com"));
        assertThrows(ResourceNotFoundException.class,
                () -> service.getLoginAccount(987654321L));
    }
}
