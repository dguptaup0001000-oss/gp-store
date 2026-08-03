package com.gpstore.controller;

import com.gpstore.dto.request.FcmTokenRequest;
import com.gpstore.entity.Customer;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.CustomerService;
import jakarta.validation.Valid;
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

    // Admin only - deactivating also force-logs-out every device they're
    // signed into (see CustomerService.setAccountActive's doc comment).
    @PutMapping("/{id}/active")
    public Customer setAccountActive(@PathVariable Long id, @RequestParam boolean active) {
        return customerService.setAccountActive(id, active);
    }

    // The customer's own profile - this didn't exist before at all.
    @GetMapping("/me")
    public Customer getMyProfile() {
        return customerService.getOwnProfile(currentUser.customerId());
    }

    // Deliberately narrow: name + mobile only, never password/role here.
    // email can be ADDED if the account doesn't have one yet (see
    // CustomerService.updateOwnProfile's doc comment for why this is
    // add-only, not change-anytime).
    @PutMapping("/me")
    public Customer updateMyProfile(@RequestBody Map<String, String> request) {
        return customerService.updateOwnProfile(
                currentUser.customerId(),
                request.get("fullName"),
                request.get("mobileNumber"),
                request.get("email"));
    }

    // Registers/refreshes this account's push notification device token -
    // called on every app start. Works for both customers and delivery
    // partners (a partner logs in through the same Customer account, see
    // DeliveryPartnerService's doc comment), which is why this lives here
    // rather than duplicated under /api/delivery-partners too.
    @PutMapping("/me/fcm-token")
    public void updateMyFcmToken(@Valid @RequestBody FcmTokenRequest request) {
        customerService.updateMyFcmToken(currentUser.customerId(), request.getFcmToken());
    }

    // Google Play Account Deletion Requirement - see the doc comment on
    // CustomerService.deleteOwnAccount for what this actually does
    // (anonymize + end all sessions, not a literal row delete) and why.
    // No confirmation/re-auth step here deliberately - the frontend is
    // responsible for a confirmation dialog before ever calling this: once
    // it's called, session tokens are already revoked, so a "type your
    // password to confirm" step here would need to happen BEFORE the JWT
    // this request is authenticated with could itself become invalid mid-flow.
    @DeleteMapping("/me")
    public void deleteMyAccount() {
        customerService.deleteOwnAccount(currentUser.customerId());
    }
}
