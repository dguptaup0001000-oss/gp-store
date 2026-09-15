package com.gpstore.auth;

import com.gpstore.dto.AuthRequest;
import com.gpstore.entity.Customer;
import com.gpstore.exception.AuthException;
import com.gpstore.platform.PlatformOnboardingService;
import com.gpstore.platform.PlatformStaffService;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fifteen characters, and the one login they are for.
 *
 * WHAT THE CODE BUYS. Between the platform opening an account and the merchant
 * claiming it, a temporary password has travelled to a shopkeeper by whatever
 * means the owner had to hand - read aloud, photographed, forwarded. That is
 * the window where the credential is most exposed, and it is the only window
 * this code covers. Afterwards it is spent (§28): asking for it every time
 * would train merchants to keep it written down beside the phone, which is the
 * opposite of a second factor.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("A merchant claims their account once, with a code they then never need again")
class ActivationCodeLoginTest {

    @Autowired private AuthService auth;
    @Autowired private PlatformOnboardingService onboarding;
    @Autowired private PlatformStaffService staffService;
    @Autowired private CustomerRepository customers;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> madeShops = new ArrayList<>();
    private final List<Long> madeMerchants = new ArrayList<>();
    private final List<Long> madeCustomers = new ArrayList<>();

    private static String uniquePhone() {
        return "9" + String.format("%09d", Math.abs(System.nanoTime() % 1_000_000_000L));
    }

    private PlatformOnboardingService.OnboardedMerchant onboardOne() {
        String tag = "act" + System.nanoTime();
        var made = onboarding.onboard("Activate " + tag, "Owner " + tag,
                tag + "@example.test", uniquePhone(), null,
                26.7606, 83.3732, new BigDecimal("5.0"), null, false);
        madeMerchants.add(made.merchantId());
        madeShops.add(made.shopId());
        madeCustomers.add(made.ownerCustomerId());
        return made;
    }

    private static AuthRequest signIn(String email, String password, String code) {
        AuthRequest request = new AuthRequest();
        request.setEmail(email);
        request.setPassword(password);
        request.setActivationCode(code);
        return request;
    }

    @AfterEach
    void removeWhatThisMade() {
        for (Long shopId : madeShops) {
            jdbc.update("DELETE FROM shop_staff WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM delivery_pricing_settings WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM store_operations_settings WHERE shop_id = ?", shopId);
            jdbc.update("DELETE FROM audit_logs WHERE entity_id = ? AND entity_type = 'Shop'", shopId);
            jdbc.update("DELETE FROM shops WHERE id = ?", shopId);
        }
        for (Long merchantId : madeMerchants) {
            jdbc.update("DELETE FROM audit_logs WHERE entity_id = ? AND entity_type = 'Merchant'", merchantId);
            jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
        }
        for (Long customerId : madeCustomers) {
            jdbc.update("DELETE FROM refresh_tokens WHERE customer_id = ?", customerId);
            jdbc.update("DELETE FROM audit_logs WHERE entity_id = ? AND entity_type = 'Customer'", customerId);
            jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        }
        madeShops.clear();
        madeMerchants.clear();
        madeCustomers.clear();
    }

    // ------------------------------------------------------- the code itself

    @Test
    @DisplayName("it is fifteen characters, with letters and digits, and never the same twice")
    void theShapeSpecifiedIsTheShapeGenerated() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 500; i++) {
            String code = ActivationCodes.generate();
            assertEquals(15, code.length(), "§23: exactly fifteen characters");
            assertTrue(code.chars().anyMatch(Character::isLetter), "§23: letters");
            assertTrue(code.chars().anyMatch(Character::isDigit), "§23: and numbers");
            assertTrue(ActivationCodes.looksWellFormed(code));
            // NOT SEQUENTIAL, and 500 draws with no repeat is what that looks
            // like from the outside. A counter would collide here immediately.
            assertTrue(seen.add(code), "a code repeated within 500 draws: it is not random");
        }
    }

    @Test
    @DisplayName("what is stored is not the code")
    void theSecretIsNeverAtRest() {
        var made = onboardOne();
        Customer owner = customers.findById(made.ownerCustomerId()).orElseThrow();

        assertNotNull(made.activationCode());
        assertNotNull(owner.getActivationCodeHash());
        // THE WHOLE POINT. A database dump, a backup, or a support engineer
        // reading the row finds a fingerprint and cannot work back to the code.
        assertNotEquals(made.activationCode(), owner.getActivationCodeHash());
        assertFalse(owner.getActivationCodeHash().contains(made.activationCode()));
        assertEquals(64, owner.getActivationCodeHash().length(), "a SHA-256 hex digest");
    }

    // ------------------------------------------------------ the first login

    @Test
    @DisplayName("the first login needs the email, the password and the code")
    void theClaimingLoginTakesAllThree() {
        var made = onboardOne();
        var response = auth.login(signIn(made.ownerEmail(), made.oneTimePassword(),
                made.activationCode()));

        assertNotNull(response.getToken());
        // AND LANDS ON THE PASSWORD SCREEN. The account was opened with a
        // password somebody else chose, so it is not theirs until they change
        // it - that is what makes their later actions their own.
        assertTrue(response.isMustChangePassword());
    }

    @Test
    @DisplayName("without the code, the password alone is not enough")
    void aLeakedPasswordIsNotAnAccount() {
        var made = onboardOne();
        // THE CASE THE CODE EXISTS FOR: somebody has the temporary password -
        // read off a screen, forwarded in a message - and nothing else.
        assertThrows(AuthException.class,
                () -> auth.login(signIn(made.ownerEmail(), made.oneTimePassword(), null)));
    }

    @Test
    @DisplayName("a wrong code is refused even with the right password")
    void theCodeIsActuallyChecked() {
        var made = onboardOne();
        assertThrows(AuthException.class, () -> auth.login(
                signIn(made.ownerEmail(), made.oneTimePassword(), ActivationCodes.generate())));
    }

    @Test
    @DisplayName("the first-login code is exactly fifteen characters")
    void shorterAndLongerCodesAreRefused() {
        var made = onboardOne();

        assertThrows(AuthException.class, () -> auth.login(
                signIn(made.ownerEmail(), made.oneTimePassword(), "A23456789BCDEF")));
        assertThrows(AuthException.class, () -> auth.login(
                signIn(made.ownerEmail(), made.oneTimePassword(), "A23456789BCDEFGH")));
    }

    @Test
    @DisplayName("an expired code is refused even with the right temporary password")
    void expiredCodesAreRefused() {
        var made = onboardOne();
        Customer owner = customers.findById(made.ownerCustomerId()).orElseThrow();
        owner.setActivationCodeIssuedAt(java.time.LocalDateTime.now().minusDays(8));
        customers.save(owner);

        AuthException refused = assertThrows(AuthException.class, () -> auth.login(
                signIn(made.ownerEmail(), made.oneTimePassword(), made.activationCode())));
        assertTrue(refused.getMessage().toLowerCase().contains("expired"));
    }

    @Test
    @DisplayName("a wrong password is refused even with the right code")
    void theCodeDoesNotReplaceThePassword() {
        var made = onboardOne();
        assertThrows(AuthException.class, () -> auth.login(
                signIn(made.ownerEmail(), "not-the-password", made.activationCode())));
    }

    @Test
    @DisplayName("another merchant's code does not open this account")
    void codesAreNotInterchangeable() {
        var mine = onboardOne();
        var theirs = onboardOne();
        assertThrows(AuthException.class, () -> auth.login(
                signIn(mine.ownerEmail(), mine.oneTimePassword(), theirs.activationCode())));
    }

    // ------------------------------------------------------ and afterwards

    @Test
    @DisplayName("after the claim the code is spent, and normal login is email and password")
    void itIsNotAPermanentThirdPassword() {
        var made = onboardOne();
        auth.login(signIn(made.ownerEmail(), made.oneTimePassword(), made.activationCode()));

        // §28. A merchant signing in on Tuesday must not be asked for a code
        // they were given once and told to destroy.
        var again = auth.login(signIn(made.ownerEmail(), made.oneTimePassword(), null));
        assertNotNull(again.getToken());

        Customer owner = customers.findById(made.ownerCustomerId()).orElseThrow();
        assertNotNull(owner.getActivationCodeClaimedAt(), "the claim must be recorded");
    }

    @Test
    @DisplayName("the spent handover pair cannot be reused after the permanent password is set")
    void aUsedCodeCannotRestoreTheTemporaryPassword() {
        var made = onboardOne();
        auth.login(signIn(made.ownerEmail(), made.oneTimePassword(), made.activationCode()));
        auth.changePassword(made.ownerCustomerId(), made.oneTimePassword(), "TheirOwnPass42");

        assertThrows(AuthException.class, () -> auth.login(
                signIn(made.ownerEmail(), made.oneTimePassword(), made.activationCode())));

        var later = auth.login(signIn(made.ownerEmail(), "TheirOwnPass42", null));
        assertNotNull(later.getToken());
        assertFalse(later.isMustChangePassword());
    }

    @Test
    @DisplayName("a reissued code kills the old one immediately")
    void reissueRevokesRatherThanAdds() {
        var made = onboardOne();
        var reissued = staffService.reissueActivationCode(made.ownerCustomerId(), "lost in transit");

        assertNotNull(reissued.activationCode());
        assertNotEquals(made.activationCode(), reissued.activationCode());

        // TWO LIVE CODES WOULD MEAN A LEAKED ONE STAYS USABLE after the
        // merchant was told it had been replaced.
        assertThrows(AuthException.class, () -> auth.login(
                signIn(made.ownerEmail(), made.oneTimePassword(), made.activationCode())));

        var response = auth.login(signIn(made.ownerEmail(), made.oneTimePassword(),
                reissued.activationCode()));
        assertNotNull(response.getToken());
    }

    @Test
    @DisplayName("a reissue re-arms the claim, so the new code is actually asked for")
    void aReissuedCodeIsNotBornSpent() {
        var made = onboardOne();
        auth.login(signIn(made.ownerEmail(), made.oneTimePassword(), made.activationCode()));

        staffService.reissueActivationCode(made.ownerCustomerId(), "credential leaked");

        // Without clearing claimedAt the new code would be handed over and
        // never asked for - a credential that protects nothing.
        assertThrows(AuthException.class,
                () -> auth.login(signIn(made.ownerEmail(), made.oneTimePassword(), null)));
    }

    @Test
    @DisplayName("no reissue ever records the secret it created")
    void theAuditTrailIsNotAPlaceToStealFrom() {
        var made = onboardOne();
        var reissued = staffService.reissueActivationCode(made.ownerCustomerId(), "lost");

        List<String> entries = jdbc.queryForList(
                "SELECT coalesce(details, '') FROM audit_logs WHERE entity_type = 'Customer' "
                        + "AND entity_id = ?", String.class, made.ownerCustomerId());
        assertFalse(entries.isEmpty(), "the reissue must be recorded at all");
        for (String entry : entries) {
            assertFalse(entry.contains(reissued.activationCode()),
                    "an audit log carrying the secret is a second place to steal it from");
            assertFalse(entry.contains(made.activationCode()));
        }
        assertTrue(entries.stream().anyMatch(e -> e.contains("lost")),
                "§30 asks for the reason, and a reissue nobody can account for is the problem");
    }

    @Test
    @DisplayName("a merchant who lost the code is not locked out for ever")
    void acompletedResetCountsAsClaimingTheAccount() {
        var made = onboardOne();
        Customer owner = customers.findById(made.ownerCustomerId()).orElseThrow();

        // The honest case this protects: the slip of paper went missing before
        // the merchant ever signed in. They do a password reset, which sends an
        // OTP to the email or phone the platform registered for them.
        //
        // Demanding the code afterwards would refuse ONLY this merchant.
        // Somebody who merely saw the temporary password cannot get an OTP
        // delivered to somebody else's phone; somebody who controls that phone
        // can reset whether a code exists or not. So the rule would buy
        // nothing and cost a support call.
        owner.setActivationCodeClaimedAt(java.time.LocalDateTime.now());
        customers.save(owner);

        var response = auth.login(signIn(made.ownerEmail(), made.oneTimePassword(), null));
        assertNotNull(response.getToken());
    }

    @Test
    @DisplayName("accounts that predate activation codes still sign in with two things")
    void nothingWasBrokenForAccountsThatAlreadyExisted() {
        var made = onboardOne();
        // Exactly the shape of every account created before this feature: a
        // password, and no code. Nothing was backfilled, so this is what Shop
        // #1's owner looks like.
        Customer owner = customers.findById(made.ownerCustomerId()).orElseThrow();
        owner.setActivationCodeHash(null);
        owner.setActivationCodeIssuedAt(null);
        owner.setActivationCodeClaimedAt(null);
        customers.save(owner);

        var response = auth.login(signIn(made.ownerEmail(), made.oneTimePassword(), null));
        assertNotNull(response.getToken());
    }
}
