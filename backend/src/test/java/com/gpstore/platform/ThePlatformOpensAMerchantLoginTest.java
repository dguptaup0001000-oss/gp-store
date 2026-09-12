package com.gpstore.platform;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Role;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.security.CustomerAccountStatusService;
import com.gpstore.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The platform owner opens a merchant's login, and stops being able to use it.
 *
 * WHAT THIS EXISTS TO PROTECT. Before this, no API could make an account an
 * ADMIN - onboarding a merchant meant SQL on the box. The obvious fix is a
 * route that creates an account with a role and a password, and that fix is
 * the dangerous one: a platform owner who knows a merchant's working
 * password makes every action that merchant takes deniable, and the audit
 * trail this codebase keeps becomes worthless in the dispute it exists for.
 *
 * So the capability is real and the credential is temporary. These tests are
 * mostly about the second half.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("The platform opens a merchant login it cannot keep")
class ThePlatformOpensAMerchantLoginTest {

    @Autowired private PlatformStaffService staff;
    @Autowired private CustomerRepository customers;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AuthService authService;
    @Autowired private CustomerAccountStatusService accountStatus;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> opened = new ArrayList<>();
    private final String tag = "staffopen" + System.nanoTime();

    private String email(String kind) {
        return tag + "-" + kind + "@example.test";
    }

    private PlatformStaffService.OpenedAccount open(String kind, Role role) {
        PlatformStaffService.OpenedAccount account =
                staff.openAccount("Merchant " + kind, email(kind), null, role);
        opened.add(account.customerId());
        return account;
    }

    @AfterEach
    void removeWhatThisMade() {
        for (Long id : opened) {
            jdbc.update("DELETE FROM refresh_tokens WHERE customer_id = ?", id);
            jdbc.update("DELETE FROM audit_logs WHERE entity_id = ? AND entity_type = 'Customer'", id);
            jdbc.update("DELETE FROM customers WHERE id = ?", id);
        }
        opened.clear();
    }

    // ------------------------------------------------- the capability itself

    @Test
    @DisplayName("a merchant's ADMIN login can be opened, which was impossible before")
    void opensAnAdminLogin() {
        PlatformStaffService.OpenedAccount account = open("owner", Role.ADMIN);

        Customer saved = customers.findById(account.customerId()).orElseThrow();
        assertEquals(Role.ADMIN, saved.getRole(),
                "no API could set this role at all before - the only one ever assigned in "
                        + "code was DELIVERY_BOY, which is why onboarding needed SQL");
        assertTrue(Boolean.TRUE.equals(saved.getActive()));
        assertFalse(Boolean.TRUE.equals(saved.getVerified()),
                "the platform vouched for the business, not for this mailbox");
    }

    @Test
    @DisplayName("the one-time password works, and is the only copy there is")
    void theOneTimePasswordIsUsableAndUnstored() {
        PlatformStaffService.OpenedAccount account = open("usable", Role.ADMIN);
        Customer saved = customers.findById(account.customerId()).orElseThrow();

        assertTrue(passwordEncoder.matches(account.oneTimePassword(), saved.getPassword()),
                "the password handed over has to actually open the account");
        assertNotEquals(account.oneTimePassword(), saved.getPassword(),
                "WHAT IS STORED IS A HASH. A readable column would make the platform owner's "
                        + "copy permanent, which is the whole thing being avoided.");

        // Not in the audit trail either. The trail must record that a
        // privileged account was opened - and not the credential.
        List<String> details = jdbc.queryForList(
                "SELECT coalesce(details,'') FROM audit_logs WHERE entity_id = ? "
                        + "AND entity_type = 'Customer'", String.class, account.customerId());
        assertFalse(details.stream().anyMatch(d -> d.contains(account.oneTimePassword())),
                "the one-time password reached the audit log: " + details);
        assertTrue(details.stream().anyMatch(d -> d.contains("role=ADMIN")),
                "opening a privileged account is exactly what an audit trail is for");
    }

    // --------------------------------------------- it cannot stay shared

    @Test
    @DisplayName("the account owes a password change from the moment it exists")
    void arrivesOwingAChange() {
        PlatformStaffService.OpenedAccount account = open("owing", Role.ADMIN);

        assertTrue(accountStatus.resolve(account.customerId()).mustChangePassword(),
                "JwtFilter reads this snapshot on every request and refuses everything but "
                        + "the change while it is true");
    }

    @Test
    @DisplayName("changing the password clears the debt and the platform's copy stops working")
    void changingItEndsThePlatformsCopy() {
        PlatformStaffService.OpenedAccount account = open("changed", Role.ADMIN);
        String handedOver = account.oneTimePassword();

        authService.changePassword(account.customerId(), handedOver, "TheirOwnPass42");

        Customer after = customers.findById(account.customerId()).orElseThrow();
        assertFalse(Boolean.TRUE.equals(after.getMustChangePassword()),
                "the account has chosen its own password and must not stay locked to one route");
        assertFalse(passwordEncoder.matches(handedOver, after.getPassword()),
                "THE POINT OF THE WHOLE FEATURE. After this the platform owner's copy is "
                        + "useless, so the merchant's actions are their own in a dispute.");
        assertFalse(accountStatus.resolve(account.customerId()).mustChangePassword(),
                "the cached snapshot must be invalidated, or the merchant is refused their "
                        + "own app for two seconds after doing what they were told");
    }

    @Test
    @DisplayName("the one-time password cannot be 'changed' to itself")
    void cannotChangeItToItself() {
        PlatformStaffService.OpenedAccount account = open("samesame", Role.ADMIN);
        String handedOver = account.oneTimePassword();

        assertThrows(RuntimeException.class,
                () -> authService.changePassword(account.customerId(), handedOver, handedOver),
                "a change to the same value would leave the account working on the credential "
                        + "the platform owner still knows, which is exactly what the forced "
                        + "change exists to end");

        assertTrue(accountStatus.resolve(account.customerId()).mustChangePassword(),
                "and the debt must survive the attempt");
    }

    // ------------------------------------------------------- what it refuses

    @Test
    @DisplayName("the platform cannot open a second platform owner")
    void cannotOpenAnotherPlatformOwner() {
        for (Role forbidden : new Role[] {Role.SUPER_ADMIN, Role.PLATFORM_ADMIN}) {
            assertThrows(RuntimeException.class,
                    () -> staff.openAccount("Second owner", email("second"), null, forbidden),
                    "creating your own authority is escalation even when you already hold it: "
                            + "it makes a platform owner whose password you chose. " + forbidden);
        }
    }

    @Test
    @DisplayName("a rider is not opened here - their shop comes from their roster row")
    void cannotOpenARider() {
        assertThrows(RuntimeException.class,
                () -> staff.openAccount("Rider", email("rider"), null, Role.DELIVERY_BOY),
                "a rider minted here would be on nobody's roster and therefore in no shop");
    }

    @Test
    @DisplayName("a customer is not opened here either")
    void cannotOpenACustomer() {
        assertThrows(RuntimeException.class,
                () -> staff.openAccount("Shopper", email("shopper"), null, Role.CUSTOMER),
                "registration already does this and needs no privilege");
    }

    @Test
    @DisplayName("an email that already exists is refused, in any case")
    void refusesADuplicateEmail() {
        PlatformStaffService.OpenedAccount first = open("dup", Role.ADMIN);

        assertThrows(RuntimeException.class,
                () -> staff.openAccount("Twin", first.email().toUpperCase(), null, Role.ADMIN),
                "a case-variant twin could never be reached by the exact-match login, and "
                        + "would leave the owner sure they had made a working account");
    }

    @Test
    @DisplayName("a shopper's password cannot be reset from the platform console")
    void cannotResetACustomersPassword() {
        Customer shopper = new Customer();
        shopper.setFullName("A Shopper");
        shopper.setEmail(email("victim"));
        shopper.setPassword(passwordEncoder.encode("TheirOwnPass42"));
        shopper.setRole(Role.CUSTOMER);
        shopper.setActive(Boolean.TRUE);
        shopper.setEnabled(Boolean.TRUE);
        Long id = customers.save(shopper).getId();
        opened.add(id);

        assertThrows(RuntimeException.class, () -> staff.resetPassword(id),
                "RESETTING A CUSTOMER AND READING THE NEW PASSWORD would be a far larger hole "
                        + "than the one this service was written to close - it is a back door "
                        + "into any shopper's account");

        Customer untouched = customers.findById(id).orElseThrow();
        assertTrue(passwordEncoder.matches("TheirOwnPass42", untouched.getPassword()),
                "and the refusal must not have changed the password on the way out");
    }

    // ----------------------------------------------------------- the reset

    @Test
    @DisplayName("a reset issues a different password and puts the account back in debt")
    void resetIssuesANewOne() {
        PlatformStaffService.OpenedAccount first = open("reset", Role.ADMIN);
        authService.changePassword(first.customerId(), first.oneTimePassword(), "TheirOwnPass42");

        PlatformStaffService.OpenedAccount again = staff.resetPassword(first.customerId());

        assertNotEquals(first.oneTimePassword(), again.oneTimePassword(),
                "each reset mints a different credential - it is not idempotent");
        Customer after = customers.findById(first.customerId()).orElseThrow();
        assertTrue(passwordEncoder.matches(again.oneTimePassword(), after.getPassword()));
        assertFalse(passwordEncoder.matches("TheirOwnPass42", after.getPassword()),
                "the password the merchant had chosen is gone - that is what a reset means");
        assertTrue(accountStatus.resolve(first.customerId()).mustChangePassword(),
                "and they are asked to choose again rather than left on the platform's pick");
    }

    @Test
    @DisplayName("two accounts never get the same one-time password")
    void passwordsAreNotPredictable() {
        String a = open("randa", Role.ADMIN).oneTimePassword();
        String b = open("randb", Role.MANAGER).oneTimePassword();

        assertNotEquals(a, b);
        assertTrue(a.length() >= 10, "the policy floor is 10 and the forced change must be "
                + "satisfiable with what was issued: " + a.length());
        // No l/1/I/O/0: this gets read off one screen and typed into another.
        assertFalse(a.matches(".*[lIO01].*"), "ambiguous glyphs turn a credential into a "
                + "support call: " + a);
    }
}
