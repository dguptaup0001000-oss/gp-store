package com.gpstore.controller;

import com.gpstore.config.PageRequests;
import com.gpstore.dto.request.LocationUpdateRequest;
import com.gpstore.dto.response.WorkerLoginAccountView;
import com.gpstore.entity.DeliveryPartner;
import com.gpstore.security.AdminPermission;
import com.gpstore.security.CurrentUser;
import com.gpstore.security.RolePermissions;
import com.gpstore.service.DeliveryPartnerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/delivery-partners")
public class DeliveryPartnerController {

    private final DeliveryPartnerService service;
    private final CurrentUser currentUser;

    public DeliveryPartnerController(DeliveryPartnerService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    // Admin only (enforced in SecurityConfig).
    @PostMapping
    public DeliveryPartner save(@RequestBody DeliveryPartner partner) {
        return service.save(partner);
    }

    // Admin only (enforced in SecurityConfig). List JSON for the existing
    // admin UI; page/size cap the query so findAll() cannot dump the roster.
    @GetMapping
    public List<DeliveryPartner> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return service.getAll(PageRequests.of(page, size));
    }

    @GetMapping("/available")
    public List<DeliveryPartner> getAvailablePartners() {
        return service.getAvailablePartners();
    }

    // Admin only (enforced in SecurityConfig) - full bulk update with no
    // ownership scoping, unlike the self-service endpoint below.
    @PutMapping
    public DeliveryPartner update(@RequestBody DeliveryPartner partner) {
        return service.update(partner);
    }

    // WHICH ACCOUNT THIS RIDER SIGNS IN WITH.
    //
    // Admin only, by the same /api/delivery-partners/** rule as the rest of
    // roster management - and it needs to be, because linking an account
    // GRANTS it the DELIVERY_BOY role.
    //
    // Placed above the /me/** routes purely for readability; SecurityConfig
    // orders the rules, not this file.
    @GetMapping("/{id}/login-account")
    public WorkerLoginAccountView getLoginAccount(@PathVariable Long id) {
        return service.getLoginAccount(id);
    }

    @PutMapping("/{id}/login-account")
    public WorkerLoginAccountView linkLoginAccount(
            @PathVariable Long id, @Valid @RequestBody LinkLoginAccountRequest request) {
        // Derived from the authenticated role on the server, exactly the way
        // JwtFilter derives authorities - never taken from the request body.
        boolean actorManagesAccounts = RolePermissions
                .forRoleName(currentUser.get().getRole())
                .contains(AdminPermission.CUSTOMERS_MANAGE);
        return service.linkLoginAccount(
                id, request.getEmail(), request.getPassword(), actorManagesAccounts);
    }

    @DeleteMapping("/{id}/login-account")
    public WorkerLoginAccountView unlinkLoginAccount(@PathVariable Long id) {
        return service.unlinkLoginAccount(id);
    }

    public static class LinkLoginAccountRequest {
        /** The address the rider will type into the worker app. */
        @NotBlank
        @Email
        private String email;

        /**
         * The password the shop is giving this rider.
         *
         * WRITE ONLY, and the annotation is what enforces it: without
         * Access.WRITE_ONLY, a request body echoed back in a validation error
         * - or this class ever being used as a response - would put a plaintext
         * password on the wire. Nothing reads it back out.
         *
         * Optional on an account that already has one, so re-saving a rider
         * does not force the shopkeeper to retype it. Required otherwise; the
         * service decides, because only it knows whether the account exists.
         * Length is checked there too - a @Size here would reject the empty
         * "leave it alone" case.
         */
        @com.fasterxml.jackson.annotation.JsonProperty(
                access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
        private String password;

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        /**
         * No password, ever, in a log line or a stack trace. The default
         * toString of a class like this is one @Slf4j call away from writing a
         * credential to disk.
         */
        @Override
        public String toString() {
            return "LinkLoginAccountRequest{email=" + email + ", password=***}";
        }
    }

    // A delivery partner setting their OWN availability (e.g. going off
    // duty) - resolved from their own account, never a client-supplied id,
    // so this can never touch anyone else's record.
    @PutMapping("/me/availability")
    public DeliveryPartner setMyAvailability(@RequestParam boolean available) {
        return service.setMyAvailability(currentUser.customerId(), available);
    }

    // A delivery partner viewing their OWN roster record - needed so the
    // app can show real current availability on load, not a guess.
    @GetMapping("/me")
    public DeliveryPartner getMyProfile() {
        return service.getByAccountIdOrThrow(currentUser.customerId());
    }

    // A delivery partner's own app pushing its live GPS position (called
    // every few seconds while on a run) - resolved from their own account,
    // never a client-supplied id, so this can never spoof someone else's
    // location.
    @PutMapping("/me/location")
    public DeliveryPartner updateMyLocation(@Valid @RequestBody LocationUpdateRequest request) {
        return service.updateMyLocation(currentUser.customerId(), request.getLatitude(),
                request.getLongitude(), request.getAccuracyMeters());
    }

}