package com.gpstore.controller;

import com.gpstore.dto.DeleteAccountRequest;
import com.gpstore.dto.UpdateProfileRequest;
import com.gpstore.dto.request.FcmTokenRequest;
import com.gpstore.entity.Customer;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.CustomerService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

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
    public Page<Customer> getAllCustomers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return customerService.getAllCustomers(pageable);
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
    public Customer updateMyProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return customerService.updateOwnProfile(
                currentUser.customerId(),
                request.getFullName(),
                request.getMobileNumber(),
                request.getEmail(),
                request.getCurrentPassword());
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

    // Called on logout, before the client discards its tokens - see
    // CustomerService.clearMyFcmToken for why leaving the old one in place
    // sends one account's order pushes to whoever signs in next on the same
    // phone. Separate from PUT rather than "PUT with an empty body" because
    // FcmTokenRequest is @NotBlank, and relaxing that would let a genuine
    // registration silently store nothing.
    @DeleteMapping("/me/fcm-token")
    public void clearMyFcmToken() {
        customerService.clearMyFcmToken(currentUser.customerId());
    }

    // Google Play Account Deletion Requirement - see the doc comment on
    // CustomerService.deleteOwnAccount. The frontend still asks the user to
    // type DELETE; that is not authentication. The current password in this
    // body is. A stolen access token alone must not destroy the account.
    @DeleteMapping("/me")
    public void deleteMyAccount(@Valid @RequestBody DeleteAccountRequest request) {
        customerService.deleteOwnAccount(currentUser.customerId(), request.getCurrentPassword());
    }
}
