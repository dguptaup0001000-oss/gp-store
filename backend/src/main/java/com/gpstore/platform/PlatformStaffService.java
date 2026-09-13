package com.gpstore.platform;

import com.gpstore.auth.PasswordPolicy;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Role;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.security.CustomerAccountStatusService;
import com.gpstore.service.AuditLogService;
import com.gpstore.service.RefreshTokenService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.Set;

/**
 * The platform owner opens a merchant's login.
 *
 * THE HOLE THIS FILLS. Until now NO API could make an account an ADMIN. The
 * only role ever assigned in code was DELIVERY_BOY, by
 * DeliveryPartnerService, and the create-customer endpoint deliberately
 * ignores a role in its body - CreateCustomerCannotEscalateTest asserts that
 * and says why in its own message: "that account's password was chosen in
 * the same request, so it is a login as that role". The consequence was that
 * onboarding a real merchant required direct SQL on the box, which is why
 * scripts/verify/onboard_second_shop.sh demands an account that already
 * exists and refuses to make one.
 *
 * That guard was right about the danger and wrong about the remedy. The
 * danger is a LOWER role minting a higher one and choosing its password in
 * the same breath. The remedy is not "nobody may ever set a role" - it is
 * that only the platform owner may, only downwards, and never by choosing
 * the password.
 *
 * THE PLATFORM OWNER NEVER KNOWS THE FINAL PASSWORD. The account is created
 * with a one-time password this class generates - the caller cannot supply
 * one - and Customer.mustChangePassword is set, which JwtFilter honours by
 * refusing every route but the change itself. So the owner can hand over
 * credentials, and the moment the merchant uses them the owner no longer
 * has them.
 *
 * WHY THAT MATTERS MORE THAN THE CONVENIENCE IT COSTS. If the platform held
 * a merchant's working password, every action that merchant takes would be
 * deniable - "the platform has my login" - and the audit trail this codebase
 * keeps would be worth nothing in the dispute it exists for. Reset is
 * supported; reading is not, and there is deliberately no code path that
 * returns an existing password or stores a readable copy of one.
 */
@Service
public class PlatformStaffService {

    /**
     * Roles the platform owner may open an account for.
     *
     * WRITTEN AS AN ALLOW-LIST, and every absence is a decision:
     *
     * <ul>
     *   <li>SUPER_ADMIN and PLATFORM_ADMIN - creating your own authority is
     *       escalation even when you already hold it, because it makes a
     *       second platform owner whose password you chose. A second owner
     *       should be a deliberate act at the database, not a button.
     *   <li>CUSTOMER - registration already does this, and it needs no
     *       privilege.
     *   <li>DELIVERY_BOY - a rider's account is created by
     *       DeliveryPartnerService against the roster, which is where their
     *       shop comes from. Minting one here would produce a rider on
     *       nobody's roster and therefore in no shop.
     * </ul>
     */
    private static final Set<Role> OPENABLE = EnumSet.of(
            Role.ADMIN,
            Role.MANAGER,
            Role.INVENTORY_MANAGER,
            Role.ORDER_MANAGER,
            Role.DELIVERY_MANAGER,
            Role.SUPPORT);

    /**
     * No l/1/I/O/0 - this password gets read off one screen and typed into
     * another, by a person, once. An ambiguous glyph turns a working
     * credential into a support call.
     */
    private static final String ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final int GENERATED_LENGTH = 14;

    private final CustomerRepository customers;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLog;
    private final CustomerAccountStatusService accountStatus;
    private final RefreshTokenService refreshTokens;
    private final SecureRandom random = new SecureRandom();

    public PlatformStaffService(CustomerRepository customers,
                                PasswordEncoder passwordEncoder,
                                AuditLogService auditLog,
                                CustomerAccountStatusService accountStatus,
                                RefreshTokenService refreshTokens) {
        this.customers = customers;
        this.passwordEncoder = passwordEncoder;
        this.auditLog = auditLog;
        this.accountStatus = accountStatus;
        this.refreshTokens = refreshTokens;
    }

    /** An account and the one-time password to hand over with it. */
    public record OpenedAccount(Long customerId, String email, String role,
                                String oneTimePassword) {}

    /**
     * Opens a staff login and returns the only copy of its password.
     *
     * RETURNED, NOT STORED. What goes in the database is the bcrypt hash,
     * like every other password. The plaintext exists in this response and
     * nowhere else - not in the audit log, not in a column, not in a second
     * table "for support". If the owner loses it before handing it over,
     * {@link #resetPassword} makes a new one; there is no way to recover
     * this one, and that is the property being bought.
     */
    @Transactional
    public OpenedAccount openAccount(String fullName, String email, String mobileNumber, Role role) {
        if (role == null || !OPENABLE.contains(role)) {
            throw new BadRequestException(
                    "The platform can open an account for " + OPENABLE + ". "
                            + "A second platform owner is a deliberate change at the database, "
                            + "a customer registers themselves, and a rider's account is created "
                            + "with their roster row.");
        }
        String cleanEmail = require(email, "An email is the login, so it is required");
        String cleanName = require(fullName, "A name is required");
        // IGNORE-CASE ON PURPOSE, though login matches exactly. Creating
        // "Owner@shop.com" beside an existing "owner@shop.com" would make a
        // twin that the exact-match login can never reach, and leave the
        // owner certain they had created a working account. Refusing the
        // wider set is the cheap side of that mistake.
        if (customers.findByEmailIgnoreCase(cleanEmail).isPresent()) {
            throw new ConflictException("An account with that email already exists.");
        }

        // THE PHONE IS UNIQUE TOO, and saying so is not symmetry for its own
        // sake. customers.mobile_number carries a UNIQUE constraint, so
        // without this check a duplicate arrives as a
        // DataIntegrityViolationException - a 500 with a constraint name in
        // it - instead of a sentence naming the problem.
        //
        // It is the likely collision, not the unlikely one: a shopkeeper
        // being onboarded may well already have a customer account on
        // GP-STORE under the same number, and the platform owner typing it
        // in has no way to know.
        String cleanPhone = blankToNull(mobileNumber);
        if (cleanPhone != null && customers.findByMobileNumber(cleanPhone).isPresent()) {
            throw new ConflictException(
                    "An account already uses that phone number. Leave it out, or use "
                            + "the number this person actually wants on their staff account.");
        }

        Customer staff = new Customer();
        staff.setFullName(cleanName);
        staff.setEmail(cleanEmail);
        staff.setMobileNumber(cleanPhone);
        staff.setRole(role);
        staff.setActive(Boolean.TRUE);
        staff.setEnabled(Boolean.TRUE);
        // THE ACCOUNT IS NOT VERIFIED BY BEING CREATED. The platform owner
        // vouched for the business, not for this mailbox.
        staff.setVerified(Boolean.FALSE);

        String oneTime = generatePassword();
        staff.setPassword(passwordEncoder.encode(oneTime));
        staff.setMustChangePassword(Boolean.TRUE);

        Customer saved = customers.save(staff);

        // ROLE AND ID ONLY. The audit trail must record that the platform
        // opened a privileged account - that is exactly the kind of act it
        // exists for - and must not record the credential.
        auditLog.log("STAFF_ACCOUNT_OPENED", "Customer", saved.getId(),
                "role=" + role.name() + ", mustChangePassword=true");

        return new OpenedAccount(saved.getId(), saved.getEmail(), role.name(), oneTime);
    }

    /**
     * Issues a new one-time password for an existing staff account.
     *
     * THE RECOVERY PATH, and the only one. A merchant who cannot get in
     * needs a new password, not a look at the old one - so this replaces it
     * and puts the account back into "must change", exactly as if it had
     * just been opened.
     *
     * EVERY EXISTING SESSION DIES. Refresh tokens are revoked, so somebody
     * holding the account's session cannot carry on while the owner believes
     * they have just locked them out - which is the whole point of a reset
     * when the reason for it is that the credential leaked.
     */
    @Transactional
    public OpenedAccount resetPassword(Long customerId) {
        Customer staff = customers.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        Role role = staff.getRole();
        // NOT A BACK DOOR INTO A CUSTOMER'S ACCOUNT. Without this the
        // platform console could reset any shopper's password and read the
        // new one, which is a far larger hole than the one this class was
        // written to close.
        if (role == null || !OPENABLE.contains(role)) {
            throw new BadRequestException(
                    "Only a staff account opened by the platform can be reset here. "
                            + "A customer resets their own password with an OTP.");
        }

        String oneTime = generatePassword();
        staff.setPassword(passwordEncoder.encode(oneTime));
        staff.setMustChangePassword(Boolean.TRUE);
        customers.save(staff);

        refreshTokens.revokeAllForCustomer(customerId);
        // The snapshot JwtFilter reads caches for two seconds; without this
        // the reset would not bite until it expired.
        accountStatus.invalidate(customerId);

        auditLog.log("STAFF_PASSWORD_RESET", "Customer", customerId,
                "role=" + role.name() + ", mustChangePassword=true, sessionsRevoked=true");

        return new OpenedAccount(customerId, staff.getEmail(), role.name(), oneTime);
    }

    /**
     * A password nobody chose.
     *
     * Rejected and regenerated rather than assumed acceptable: the policy
     * forbids a short denylist and requires a letter and a digit, and a
     * generator that happened to produce an all-letter string would create
     * an account whose owner cannot satisfy the change it is forced into.
     */
    private String generatePassword() {
        for (int attempt = 0; attempt < 100; attempt++) {
            StringBuilder builder = new StringBuilder(GENERATED_LENGTH);
            for (int i = 0; i < GENERATED_LENGTH; i++) {
                builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String candidate = builder.toString();
            if (PasswordPolicy.isAcceptable(candidate)) {
                return candidate;
            }
        }
        // Unreachable with this alphabet and length. Loud rather than a
        // silent weak password if it ever is not.
        throw new IllegalStateException("Could not generate an acceptable one-time password");
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(message);
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
