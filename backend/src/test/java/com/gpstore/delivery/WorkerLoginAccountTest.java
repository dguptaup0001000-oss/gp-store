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

    /** Long enough to pass the minimum. Never a real credential. */
    /**
     * Whether the person pressing Save already holds CUSTOMERS_MANAGE.
     *
     * The owner does; a DELIVERY_MANAGER, who can edit the roster and nothing
     * about accounts, does not. That difference is the whole staff rule, so
     * every call site says which one it is rather than passing a bare boolean.
     */
    private static final boolean AS_OWNER = true;
    private static final boolean AS_ROSTER_ONLY = false;

    private static final String PASSWORD = "rider-test-passphrase";

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder encoder;

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

        WorkerLoginAccountView view = service.linkLoginAccount(partner.getId(), email, PASSWORD, AS_OWNER);

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

        service.linkLoginAccount(partner.getId(), email, PASSWORD, AS_OWNER);

        // findByAccountId is what every worker endpoint resolves through.
        assertEquals(partner.getId(),
                partners.findByAccountId(account.getId()).orElseThrow().getId());
    }

    @Test
    @DisplayName("an unknown email creates the rider's account with the password given")
    void unknownEmailCreatesTheAccount() {
        DeliveryPartner partner = rosterOnlyPartner();
        String email = "newrider-" + unique() + "@gmail.com";

        // THE WHOLE POINT OF THE REWRITE. This used to be refused, sending the
        // shopkeeper away to register the rider in the CUSTOMER app first -
        // which never worked, because nothing there set a password either.
        WorkerLoginAccountView view = service.linkLoginAccount(partner.getId(), email, PASSWORD, AS_OWNER);

        assertTrue(view.linked());
        assertTrue(view.canSignIn(), "A rider the shop just set up must be able to sign in.");

        Customer created = customers.findByEmailIgnoreCase(email).orElseThrow();
        assertEquals(Role.DELIVERY_BOY, created.getRole());
        assertNotEquals(PASSWORD, created.getPassword(),
                "The password must be hashed, never stored as typed.");
        assertTrue(encoder.matches(PASSWORD, created.getPassword()),
                "and it must be the hash of what the shop actually typed.");
    }

    @Test
    @DisplayName("a shopper who becomes a rider keeps their own password")
    void existingShopperKeepsTheirPassword() {
        // THE CASE THE SHOP ACTUALLY HAS: one person who buys here and also
        // delivers, on one email. The shop must not reset the password they
        // already chose just to give them a delivery job, so a blank password
        // means "leave it alone".
        DeliveryPartner partner = rosterOnlyPartner();
        String email = "both-" + unique() + "@gmail.com";
        Customer shopper = shopper(email, encoder.encode("their-own-password"));
        String before = shopper.getPassword();

        WorkerLoginAccountView view = service.linkLoginAccount(partner.getId(), email, "", AS_OWNER);

        assertTrue(view.canSignIn(), "They could already sign in; that must not change.");
        Customer after = customers.findByEmailIgnoreCase(email).orElseThrow();
        assertEquals(before, after.getPassword(), "Their password must be byte-identical.");
        assertTrue(encoder.matches("their-own-password", after.getPassword()),
                "and must still be the one they chose.");
        assertEquals(Role.DELIVERY_BOY, after.getRole(),
                "They gain the delivery job - and RolePermissions keeps ROLE_CUSTOMER "
                        + "alongside it, so their own checkout still works.");
    }

    @Test
    @DisplayName("a password too short to be worth having is refused")
    void shortPasswordIsRefused() {
        DeliveryPartner partner = rosterOnlyPartner();

        BadRequestException thrown = assertThrows(BadRequestException.class,
                () -> service.linkLoginAccount(
                        partner.getId(), "short-" + unique() + "@gmail.com", "abc", AS_OWNER));

        assertTrue(thrown.getMessage().contains("8"), thrown.getMessage());
    }

    @Test
    @DisplayName("an existing OTP-only account is given the password it never had")
    void passwordlessAccountIsGivenOne() {
        DeliveryPartner partner = rosterOnlyPartner();
        String email = "otponly-" + unique() + "@gmail.com";
        shopper(email, null);

        // This is precisely the account the roster screen used to create: no
        // password, so no way into an app whose only door is email+password.
        // It is now completed rather than refused.
        WorkerLoginAccountView view = service.linkLoginAccount(partner.getId(), email, PASSWORD, AS_OWNER);

        assertTrue(view.canSignIn());
        assertTrue(encoder.matches(PASSWORD,
                customers.findByEmailIgnoreCase(email).orElseThrow().getPassword()));
    }

    @Test
    @DisplayName("a staff account cannot be taken over through the roster screen")
    void staffAccountCannotBeHijacked() {
        DeliveryPartner partner = rosterOnlyPartner();
        String email = "owner-" + unique() + "@gmail.com";
        Customer owner = shopper(email, "$2a$10$hashedpasswordvaluegoeshere1234567890abcd");
        owner.setRole(Role.ADMIN);
        customers.save(owner);

        // THE ESCALATION THIS CLOSES, and the reason it turns on WHO IS
        // ASKING. A DELIVERY_MANAGER holds DELIVERY_MANAGE and not
        // CUSTOMERS_MANAGE, so they can edit the roster and nothing about
        // accounts - they must not be able to choose the owner's password here
        // and then sign in as them. Linking is still allowed for them (see
        // theOwnerCanBeTheirOwnRider); writing a credential is not.
        assertThrows(ConflictException.class,
                () -> service.linkLoginAccount(partner.getId(), email, PASSWORD, AS_ROSTER_ONLY));

        Customer unchanged = customers.findByEmailIgnoreCase(email).orElseThrow();
        assertEquals(Role.ADMIN, unchanged.getRole(), "The role must not have been touched.");
        assertFalse(encoder.matches(PASSWORD, unchanged.getPassword()),
                "and neither must the password.");
        assertTrue(partners.findById(partner.getId()).orElseThrow().getAccount() == null,
                "and a refused attempt must not leave the partner half-linked.");
    }

    @Test
    @DisplayName("the owner can be their own rider, with the password they already have")
    void theOwnerCanBeTheirOwnRider() {
        // THE ONE-PERSON SHOP. The owner does the deliveries, and refusing
        // staff accounts outright left them unable to put their own address on
        // the roster at all - the worker app answered "this login is not linked
        // to a worker record" and the roster screen offered no way to fix it.
        DeliveryPartner partner = rosterOnlyPartner();
        String email = "owner-" + unique() + "@gmail.com";
        Customer owner = shopper(email, encoder.encode("the-owners-own-password"));
        owner.setRole(Role.ADMIN);
        customers.save(owner);
        String before = customers.findById(owner.getId()).orElseThrow().getPassword();

        // Blank password: they already have one, and it is not this screen's
        // business what it is.
        WorkerLoginAccountView view = service.linkLoginAccount(partner.getId(), email, "", AS_OWNER);

        assertTrue(view.linked(), "The address must now be the rider's login.");
        assertTrue(view.canSignIn(), "They already have a password, so they can sign in.");
        assertEquals(owner.getId(),
                partners.findById(partner.getId()).orElseThrow().getAccount().getId(),
                "and findByAccountId is what the worker app uses to find the roster row - "
                        + "without this link it reports no worker record.");

        Customer after = customers.findById(owner.getId()).orElseThrow();
        assertEquals(Role.ADMIN, after.getRole(),
                "Linking must not demote the owner to a delivery rider - that would strip "
                        + "every permission they have, including the one they used to do this.");
        assertEquals(before, after.getPassword(), "Their password must be byte-identical.");
        assertTrue(encoder.matches("the-owners-own-password", after.getPassword()),
                "and must still be the one they chose.");
    }

    @Test
    @DisplayName("the owner can set an email AND a password on their own account in one Save")
    void theOwnerCanSetBothHalvesOnTheirOwnAccount() {
        // WHAT THE SHOP ACTUALLY DOES. The owner types an address and a
        // password and presses Save - the same two fields every other rider
        // gets. Refusing that because the address happens to be staff left a
        // one-person shop with no way to put itself on its own roster, and
        // bought nothing: an operator holding CUSTOMERS_MANAGE can set that
        // password on the customer screens anyway.
        DeliveryPartner partner = rosterOnlyPartner();
        String email = "boss-" + unique() + "@gmail.com";
        Customer owner = shopper(email, encoder.encode("the-old-one"));
        owner.setRole(Role.ADMIN);
        customers.save(owner);

        WorkerLoginAccountView view = service.linkLoginAccount(
                partner.getId(), email, PASSWORD, AS_OWNER);

        assertTrue(view.canSignIn());
        assertEquals(owner.getId(),
                partners.findById(partner.getId()).orElseThrow().getAccount().getId(),
                "and the roster row must point at it, or the worker app still reports "
                        + "no worker record.");

        Customer after = customers.findById(owner.getId()).orElseThrow();
        assertTrue(encoder.matches(PASSWORD, after.getPassword()),
                "The password they typed is the one that now works.");
        assertEquals(Role.ADMIN, after.getRole(),
                "and setting it must not cost them their own permissions.");
    }

    @Test
    @DisplayName("a staff account without delivery access is refused, not linked into a dead end")
    void staffWithoutDeliveryAccessIsRefused() {
        // /api/worker/** admits DELIVERY_MANAGE or a delivery rider. Linking an
        // account that is neither would look like it worked in the admin app
        // and then fail at the door, which is the failure this whole thread has
        // been about. Say so at the point of the mistake instead.
        DeliveryPartner partner = rosterOnlyPartner();
        String email = "support-" + unique() + "@gmail.com";
        Customer support = shopper(email, encoder.encode("their-own-password"));
        support.setRole(Role.SUPPORT);
        customers.save(support);

        assertThrows(ConflictException.class,
                () -> service.linkLoginAccount(partner.getId(), email, "", AS_OWNER));

        assertNull(partners.findById(partner.getId()).orElseThrow().getAccount(),
                "A refused link must leave the roster row alone.");
    }

    @Test
    @DisplayName("one account cannot be the login for two riders")
    void oneAccountCannotServeTwoPartners() {
        DeliveryPartner first = rosterOnlyPartner();
        DeliveryPartner second = rosterOnlyPartner();
        String email = "shared-" + unique() + "@gmail.com";
        shopper(email, "$2a$10$hashedpasswordvaluegoeshere1234567890abcd");

        service.linkLoginAccount(first.getId(), email, PASSWORD, AS_OWNER);

        // findByAccountId returns Optional, so a second link would throw a
        // non-unique-result error on the rider's very next request - far from
        // the admin who caused it.
        assertThrows(ConflictException.class,
                () -> service.linkLoginAccount(second.getId(), email, PASSWORD, AS_OWNER));
    }

    @Test
    @DisplayName("re-linking the same account to the same rider is not a conflict")
    void relinkingTheSamePairIsIdempotent() {
        DeliveryPartner partner = rosterOnlyPartner();
        String email = "again-" + unique() + "@gmail.com";
        shopper(email, "$2a$10$hashedpasswordvaluegoeshere1234567890abcd");

        service.linkLoginAccount(partner.getId(), email, PASSWORD, AS_OWNER);
        assertDoesNotThrow(() -> service.linkLoginAccount(partner.getId(), email, PASSWORD, AS_OWNER));
    }

    @Test
    @DisplayName("an ADMIN's role and password both survive a link attempt")
    void adminIsLeftEntirelyAlone() {
        DeliveryPartner partner = rosterOnlyPartner();
        String email = "owner-" + unique() + "@gmail.com";
        Customer admin = shopper(email, "$2a$10$hashedpasswordvaluegoeshere1234567890abcd");
        admin.setRole(Role.ADMIN);
        customers.save(admin);
        String before = customers.findById(admin.getId()).orElseThrow().getPassword();

        // A password typed against a staff address by someone who cannot
        // manage accounts is refused, and a refusal must be total: no role
        // change, no password change, nothing written.
        assertThrows(ConflictException.class,
                () -> service.linkLoginAccount(partner.getId(), email, PASSWORD, AS_ROSTER_ONLY));

        Customer after = customers.findById(admin.getId()).orElseThrow();
        assertEquals(Role.ADMIN, after.getRole());
        assertEquals(before, after.getPassword(), "The admin's password must be byte-identical.");
    }

    @Test
    @DisplayName("email matching ignores case, because people type their address how they like")
    void emailMatchIsCaseInsensitive() {
        DeliveryPartner partner = rosterOnlyPartner();
        String email = "mixed-" + unique() + "@gmail.com";
        shopper(email, "$2a$10$hashedpasswordvaluegoeshere1234567890abcd");

        assertDoesNotThrow(
                () -> service.linkLoginAccount(partner.getId(), "  " + email.toUpperCase() + "  ", PASSWORD, AS_OWNER));
    }

    @Test
    @DisplayName("unlinking demotes DELIVERY_BOY back to CUSTOMER")
    void unlinkingDemotesTheRiderRole() {
        DeliveryPartner partner = rosterOnlyPartner();
        String email = "leaver-" + unique() + "@gmail.com";
        Customer account = shopper(email, "$2a$10$hashedpasswordvaluegoeshere1234567890abcd");

        service.linkLoginAccount(partner.getId(), email, PASSWORD, AS_OWNER);
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

        // Linked as an ordinary account first, then promoted, so the role
        // under test is one this endpoint did NOT grant. The property is about
        // UNLINK, which must not take away what it never gave.
        service.linkLoginAccount(partner.getId(), email, PASSWORD, AS_OWNER);
        admin.setRole(Role.ADMIN);
        customers.save(admin);

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
        service.linkLoginAccount(partner.getId(), email, PASSWORD, AS_OWNER);

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
                () -> service.linkLoginAccount(987654321L, "someone@gmail.com", PASSWORD, AS_OWNER));
        assertThrows(ResourceNotFoundException.class,
                () -> service.getLoginAccount(987654321L));
    }
}
