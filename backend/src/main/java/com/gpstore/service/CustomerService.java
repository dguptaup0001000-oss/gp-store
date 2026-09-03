package com.gpstore.service;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Role;
import com.gpstore.exception.AuthException;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.AddressRepository;
import com.gpstore.repository.CartRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.NotificationRepository;
import com.gpstore.repository.WishlistRepository;
import com.gpstore.security.CustomerAccountStatusService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final AddressRepository addressRepository;
    private final CartRepository cartRepository;
    private final WishlistRepository wishlistRepository;
    private final NotificationRepository notificationRepository;
    private final PushNotificationService pushNotificationService;
    private final CustomerAccountStatusService accountStatusService;
    private final com.gpstore.repository.CustomerAppSessionRepository appSessionRepository;

    public CustomerService(
            CustomerRepository customerRepository,
            PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokenService,
            AddressRepository addressRepository,
            CartRepository cartRepository,
            WishlistRepository wishlistRepository,
            NotificationRepository notificationRepository,
            PushNotificationService pushNotificationService,
            CustomerAccountStatusService accountStatusService,
            com.gpstore.repository.CustomerAppSessionRepository appSessionRepository) {
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.pushNotificationService = pushNotificationService;
        this.addressRepository = addressRepository;
        this.cartRepository = cartRepository;
        this.wishlistRepository = wishlistRepository;
        this.notificationRepository = notificationRepository;
        this.accountStatusService = accountStatusService;
        this.appSessionRepository = appSessionRepository;
    }

    /**
     * Admin-created customer accounts (e.g. phone order taken manually).
     * This previously never hashed the password when called outside
     * AuthService.register() - fixed here so it's not possible to accidentally
     * store a plaintext password no matter which path creates the account.
     */
    public Customer saveCustomer(Customer customer) {
        customerRepository.findByEmail(customer.getEmail()).ifPresent(existing -> {
            throw new ConflictException("An account with this email already exists");
        });

        // An admin creating a phone-order customer with no password (they'll
        // log in via OTP later, same as any OTP-auto-created account) is
        // legitimate - passwordEncoder.encode(null) throws, so this must be
        // conditional, not unconditional like it was before.
        if (customer.getPassword() != null && !customer.getPassword().isBlank()) {
            customer.setPassword(passwordEncoder.encode(customer.getPassword()));
        } else {
            customer.setPassword(null);
        }

        if (customer.getRole() == null) {
            customer.setRole(Role.CUSTOMER);
        }
        if (customer.getActive() == null) {
            customer.setActive(true);
        }
        if (customer.getEnabled() == null) {
            customer.setEnabled(true);
        }

        return customerRepository.save(customer);
    }

    public org.springframework.data.domain.Page<Customer> getAllCustomers(
            org.springframework.data.domain.Pageable pageable) {
        return customerRepository.findAll(pageable);
    }

    public Customer getByEmail(String email) {
        return customerRepository.findByEmail(email).orElse(null);
    }

    public Customer getByMobileNumber(String mobileNumber) {
        return customerRepository.findByMobileNumber(mobileNumber).orElse(null);
    }

    public Customer getById(Long id) {
        return customerRepository.findById(id).orElse(null);
    }

    /**
     * Deactivating an account (e.g. for abuse/fraud) also revokes every
     * refresh token they hold - without this, they'd keep working on any
     * device where they're already logged in, and only be blocked from a
     * FUTURE login. Re-activating does NOT restore old sessions - they'll
     * need to log in again, which is the correct behavior either way.
     */
    public Customer setAccountActive(Long customerId, boolean active) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        customer.setActive(active);
        Customer saved = customerRepository.save(customer);

        if (!active) {
            refreshTokenService.revokeAllForCustomer(customerId);
        }

        // Drop the short-lived JWT status cache immediately so a just-banned
        // access token is refused on the next request rather than for up to
        // CustomerAccountStatusService.TTL_MS. Reactivation is the same:
        // without this, a reactivated customer would still get 401 until
        // the cached "unusable" entry expired.
        accountStatusService.invalidate(customerId);

        return saved;
    }

    public Customer getOwnProfile(Long customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
    }

    /**
     * A customer can update their own name/mobile through this path, and
     * ADD an email if they don't have one yet (an OTP-only account wanting
     * to also use email+password login - this is what unblocks
     * AuthService.changePassword's own "add an email first" requirement,
     * which had no way to actually be satisfied before this existed).
     *
     * Deliberately does NOT let someone CHANGE an already-set email - doing
     * that safely needs a verification step (prove you own the new
     * address), and there's no email-sending infrastructure in this system
     * to do that with. Adding one for the first time carries much lower
     * risk: the customer is already authenticated, so it's genuinely them;
     * if they mistype it, that only makes their own future email-login
     * attempts fail, it can't be used to take over anyone else's account.
     *
     * Password and role/active are still out of scope here - password has
     * its own dedicated change-password flow, role/active are staff-only.
     *
     * Changing the mobile number requires the current password. A stolen
     * access token must not re-bind the account to an attacker's phone
     * (OTP login would then belong to them). Name and first-time email
     * add do not.
     */
    public Customer updateOwnProfile(Long customerId, String fullName, String mobileNumber,
                                     String email, String currentPassword) {
        Customer customer = getOwnProfile(customerId);

        if (mobileNumber != null && !mobileNumber.equals(customer.getMobileNumber())) {
            requireCurrentPassword(customer, currentPassword,
                    "Set a password on this account before changing your mobile number.");
            customerRepository.findByMobileNumber(mobileNumber).ifPresent(existing -> {
                throw new ConflictException("Another account already uses this mobile number");
            });
            customer.setMobileNumber(mobileNumber);
        }

        if (customer.getEmail() == null && email != null && !email.isBlank()) {
            customerRepository.findByEmail(email).ifPresent(existing -> {
                throw new ConflictException("Another account already uses this email");
            });
            customer.setEmail(email);
        }

        customer.setFullName(fullName);

        return customerRepository.save(customer);
    }

    /**
     * Self-service - registers/refreshes this account's push notification
     * device token. Called on every app start and whenever Firebase issues
     * a new token (tokens rotate periodically by design - see
     * firebase_messaging_service.dart's onTokenRefresh listener). Resolved
     * from the caller's own account, so this can never overwrite someone
     * else's token.
     */
    public void updateMyFcmToken(Long customerId, String fcmToken) {
        Customer customer = getOwnProfile(customerId);
        customer.setFcmToken(fcmToken);
        customerRepository.save(customer);

        // Subscribing every device to one shared topic here is what lets
        // NotificationService.broadcastToAll send a single FCM call instead
        // of one per customer - see PushNotificationService.ALL_CUSTOMERS_TOPIC.
        // Re-subscribing on every call (not just first registration) is
        // deliberate and cheap - FCM treats an already-subscribed token as a
        // no-op, and this guarantees a token that rotated still ends up
        // subscribed without needing separate unsubscribe/resubscribe logic.
        pushNotificationService.subscribeToTopic(fcmToken, PushNotificationService.ALL_CUSTOMERS_TOPIC);
    }

    /**
     * Detaches this account from whatever device token it currently holds -
     * called on logout, while the caller's own JWT is still valid.
     *
     * WHY LOGOUT HAS TO DO THIS. A device token identifies a PHONE, not an
     * account, but it is stored per customer row. So when one person signs
     * out of the shop phone and the next person signs in, the first account
     * still holds that phone's token - and every targeted push for the first
     * account keeps landing on a device somebody else is now using. Order
     * pushes carry a customer name and an order amount, so that is other
     * people's information arriving on the wrong screen, not merely a
     * wasted send.
     *
     * The ALL_CUSTOMERS topic subscription is deliberately left in place:
     * it is bound to the token rather than to an account and carries only
     * store-wide announcements with nothing personal in them, so unsubscribing
     * would cost a signed-out phone its store notifications for no privacy
     * gain. Login re-registers the token and re-subscribes either way.
     */
    public void clearMyFcmToken(Long customerId) {
        Customer customer = getOwnProfile(customerId);
        customer.setFcmToken(null);
        customerRepository.save(customer);
    }

    /**
     * Google Play's Account Deletion Requirement (user data policy) means
     * every app that supports account creation must offer a genuine
     * in-app self-service deletion path - see PLAY_STORE_CHECKLIST.md.
     *
     * This is ANONYMIZATION, not a hard row delete, and that's deliberate:
     * Orders/Invoices/Payments reference this customer via a foreign key,
     * and Invoice records specifically have real-world tax retention
     * requirements (GST invoices in India) that outlive any one customer's
     * account. Hard-deleting the Customer row would either violate that FK
     * constraint or silently destroy invoice history depending on cascade
     * config - both are worse than the standard e-commerce industry
     * pattern of scrubbing personal fields while retaining the transaction
     * record itself. Reviews work the same way: the review text stays (it's
     * content other customers may already be relying on), but it will now
     * display against "Deleted User" since that's what customer.fullName
     * becomes below - no separate change needed in Review itself.
     *
     * What's genuinely and permanently deleted outright, not just
     * scrubbed: refresh tokens (all sessions end immediately), saved
     * addresses, wishlist, cart/cart items, and notifications - none of
     * these have any legitimate reason to survive the account.
     *
     * mobileNumber/email are set to null rather than a placeholder string -
     * both columns are unique-constrained, and a placeholder like
     * "deleted" would collide the second time this method ever runs.
     * Customer.java's existing column comments confirm unique=true still
     * permits multiple NULLs, so this is safe.
     */
    @Transactional
    public void deleteOwnAccount(Long customerId, String currentPassword) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        requireCurrentPassword(customer, currentPassword,
                "Set a password on this account before deleting it.");

        refreshTokenService.revokeAllForCustomer(customerId);

        // Bulk deletes rather than deleteAll(findByCustomerId(...)). The old
        // form loaded every one of the account's notifications - which grow
        // for the life of the account, one per order status change - into
        // memory and issued a DELETE per row, all inside the transaction the
        // user is waiting on. Addresses and wishlist entries are smaller but
        // are the same shape of query, so they move for consistency.
        notificationRepository.deleteByCustomerId(customerId);
        wishlistRepository.deleteByCustomerIdBulk(customerId);
        addressRepository.deleteByCustomerIdBulk(customerId);
        cartRepository.findByCustomerId(customerId).ifPresent(cartRepository::delete);
        // The usage history goes too. Deleting an account anonymises the
        // customer row rather than removing it, so anything keyed on the id
        // that is not explicitly deleted here simply survives - and "how long
        // this person spent in our app" surviving a deletion request is
        // exactly the thing Play's requirement, and our own declaration in
        // docs/PLAY_STORE_DECLARATIONS.md sec. 8, say must not happen.
        appSessionRepository.deleteByCustomerIdBulk(customerId);

        customer.setFullName("Deleted User");
        customer.setMobileNumber(null);
        customer.setEmail(null);
        // Random, never-communicated value - not "no password", which would
        // mean "log in via OTP to this now-nonexistent phone number" is
        // still somehow a live path back into the account. This guarantees
        // both login methods are actually dead.
        customer.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        customer.setEnabled(false);
        customer.setActive(false);

        customerRepository.save(customer);
        accountStatusService.invalidate(customerId);
    }

    /**
     * Step-up for irreversible or identity-binding changes. OTP-only
     * accounts have no password to check; they must set one (which itself
     * requires adding an email) before they can change phone or delete.
     * MSG91 is fail-closed in production, so OTP cannot be the step-up
     * until those credentials exist.
     */
    private void requireCurrentPassword(Customer customer, String currentPassword, String noPasswordMessage) {
        if (customer.getPassword() == null || customer.getPassword().isBlank()) {
            throw new BadRequestException(noPasswordMessage);
        }
        if (currentPassword == null || currentPassword.isBlank()
                || !passwordEncoder.matches(currentPassword, customer.getPassword())) {
            throw new AuthException("Current password is incorrect");
        }
    }
}
