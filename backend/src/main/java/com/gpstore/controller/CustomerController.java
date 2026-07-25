package com.gpstore.controller;

import com.gpstore.entity.Customer;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.CustomerService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;
    private final CurrentUser currentUser;

    public CustomerController(CustomerService customerService, CurrentUser currentUser) {
        this.customerService = customerService;
        this.currentUser = currentUser;
    }

    // Admin only (enforced in SecurityConfig) - e.g. creating an account for a phone order.
    @PostMapping
    public Customer createCustomer(@RequestBody Customer customer) {
        return customerService.saveCustomer(customer);
    }

    // Admin only (enforced in SecurityConfig).
    @GetMapping
    public List<Customer> getAllCustomers() {
        return customerService.getAllCustomers();
    }

    // Admin only (enforced in SecurityConfig).
    @GetMapping("/email/{email}")
    public Customer getByEmail(@PathVariable String email) {
        return customerService.getByEmail(email);
    }

    // Admin only (enforced in SecurityConfig).
    @GetMapping("/mobile/{mobileNumber}")
    public Customer getByMobile(@PathVariable String mobileNumber) {
        return customerService.getByMobileNumber(mobileNumber);
    }

    // The customer's own profile - this didn't exist before at all.
    @GetMapping("/me")
    public Customer getMyProfile() {
        return customerService.getOwnProfile(currentUser.customerId());
    }

    // Deliberately narrow: name + mobile only, never email/password/role here.
    @PutMapping("/me")
    public Customer updateMyProfile(@RequestBody Map<String, String> request) {
        return customerService.updateOwnProfile(
                currentUser.customerId(),
                request.get("fullName"),
                request.get("mobileNumber"));
    }
}
