package com.gpstore.service;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Role;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.CustomerRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;

    public CustomerService(
            CustomerRepository customerRepository,
            PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokenService) {
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
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

    public List<Customer> getAllCustomers() {
        return customerRepository.findAll();
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
}
