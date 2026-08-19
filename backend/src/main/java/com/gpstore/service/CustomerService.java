package com.gpstore.service;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Role;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.AddressRepository;
import com.gpstore.repository.CartRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.NotificationRepository;
import com.gpstore.repository.WishlistRepository;
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

    public CustomerService(
            CustomerRepository customerRepository,
            PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokenService,
            AddressRepository addressRepository,
            CartRepository cartRepository,
            WishlistRepository wishlistRepository,
            NotificationRepository notificationRepository,
            PushNotificationService pushNotificationService) {
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.pushNotificationService = pushNotificationService;
        this.addressRepository = addressRepository;
        this.cartRepository = cartRepository;
        this.wishlistRepository = wishlistRepository;
        this.notificationRepository = notificationRepository;
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
     */
    public Customer updateOwnProfile(Long customerId, String fullName, String mobileNumber, String email) {
        Customer customer = getOwnProfile(customerId);

        if (mobileNumber != null && !mobileNumber.equals(customer.getMobileNumber())) {
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
    public void deleteOwnAccount(Long customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        refreshTokenService.revokeAllForCustomer(customerId);
        notificationRepository.deleteAll(notificationRepository.findByCustomerId(customerId));
        wishlistRepository.deleteAll(wishlistRepository.findByCustomerId(customerId));
        addressRepository.deleteAll(addressRepository.findByCustomerId(customerId));
        cartRepository.findByCustomerId(customerId).ifPresent(cartRepository::delete);

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
    }
}
