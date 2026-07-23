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

    public CustomerService(CustomerRepository customerRepository, PasswordEncoder passwordEncoder) {
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
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

        customer.setPassword(passwordEncoder.encode(customer.getPassword()));

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

    public Customer getOwnProfile(Long customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
    }

    /**
     * Deliberately narrow: a customer can update their own name/mobile through
     * this path, but NOT email, password, role, or enabled/active status.
     * Email changes need re-verification (a bigger feature); password changes
     * go through a dedicated change-password flow, not a generic profile PUT;
     * role/active are staff-only concerns.
     */
    public Customer updateOwnProfile(Long customerId, String fullName, String mobileNumber) {
        Customer customer = getOwnProfile(customerId);
        customer.setFullName(fullName);
        customer.setMobileNumber(mobileNumber);
        return customerRepository.save(customer);
    }
}
