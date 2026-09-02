package com.gpstore.service;

import com.gpstore.config.PageRequests;
import com.gpstore.dto.response.WorkerLoginAccountView;
import com.gpstore.entity.Customer;
import com.gpstore.entity.DeliveryPartner;
import com.gpstore.entity.Role;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.DeliveryPartnerRepository;
import com.gpstore.repository.DeliveryRepository;
import com.gpstore.security.AdminPermission;
import com.gpstore.security.CustomerAccountStatusService;
import com.gpstore.security.RolePermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DeliveryPartnerService {

    private final DeliveryPartnerRepository repository;
    private final DeliveryRepository deliveryRepository;
    private final CustomerRepository customerRepository;
    private final CustomerAccountStatusService accountStatusService;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    /**
     * How vague a GPS fix may be and still be worth recording, in metres.
     *
     * 500 m is deliberately generous: a phone indoors at a packing bench, or
     * one that has just woken up, legitimately reports a few hundred metres
     * and its next fix is fine. What this stops is the kilometre-scale
     * cell-tower estimate being drawn on a map as though somebody knew.
     * Configurable because the right number depends on the handsets in use.
     */
    private final double maxLocationAccuracyMeters;

    public DeliveryPartnerService(
            DeliveryPartnerRepository repository,
            DeliveryRepository deliveryRepository,
            CustomerRepository customerRepository,
            CustomerAccountStatusService accountStatusService,
            org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
            @Value("${delivery.max-location-accuracy-meters:500}") double maxLocationAccuracyMeters) {
        this.repository = repository;
        this.deliveryRepository = deliveryRepository;
        this.customerRepository = customerRepository;
        this.accountStatusService = accountStatusService;
        this.passwordEncoder = passwordEncoder;
        this.maxLocationAccuracyMeters = maxLocationAccuracyMeters;
    }

    /**
     * A DeliveryPartner row was never a login identity on its own, just a
     * name on a roster. Creating one now also creates the linked Customer
     * account (role=DELIVERY_BOY) that actually lets this person log in via
     * mobile OTP - the same OTP endpoints customers use, since the OTP flow
     * already looks up by mobile number and logs in with whatever role that
     * account has (see AuthService.verifyOtpAndAuthenticate) - no changes
     * needed there. verified=true because an admin creating this record IS
     * the verification, unlike a customer's own self-registered number.
     */
    @Transactional
    public DeliveryPartner save(DeliveryPartner partner) {
        boolean isNewPartner = partner.getId() == null;

        DeliveryPartner saved = repository.save(partner);

        if (isNewPartner && saved.getAccount() == null) {
            // saved is reassigned later in this method (line below the if-block),
            // so it can't be captured directly by the lambda. This extra
            // reference is never reassigned, so it satisfies "effectively final".
            final DeliveryPartner savedForAccount = saved;
            Customer account = customerRepository.findByMobileNumber(saved.getMobile())
                    .orElseGet(() -> {
                        Customer newAccount = new Customer();
                        newAccount.setFullName(savedForAccount.getName());
                        newAccount.setMobileNumber(savedForAccount.getMobile());
                        newAccount.setEnabled(true);
                        newAccount.setVerified(true);
                        newAccount.setActive(true);
                        return newAccount;
                    });

            // Whether brand new or an existing Customer becoming a partner
            // (e.g. someone who already shopped here before you hired them) -
            // they need DELIVERY_BOY role to pass SecurityConfig's role check
            // on any delivery endpoint, or this link would be meaningless.
            // Never downgrades an existing ADMIN, though.
            if (account.getRole() != Role.ADMIN) {
                account.setRole(Role.DELIVERY_BOY);
            }
            account = customerRepository.save(account);
            accountStatusService.invalidate(account.getId());

            saved.setAccount(account);
            saved = repository.save(saved);
        }

        return saved;
    }

    /**
     * Which account, if any, this partner signs in with.
     *
     * Transactional because {@code account} is LAZY and this application runs
     * with open-in-view=false - reading the email has to happen while the
     * session is still open, which is exactly why this returns a DTO rather
     * than letting Jackson walk the association later.
     */
    @Transactional(readOnly = true)
    public WorkerLoginAccountView getLoginAccount(Long partnerId) {
        DeliveryPartner partner = repository.findById(partnerId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery partner not found"));
        return describe(partner.getAccount());
    }

    /**
     * Gives a rider the credentials they sign in to the worker app with.
     *
     * WHY THIS CREATES THE ACCOUNT RATHER THAN LINKING ONE. The first version
     * of this refused to create anything: the shopkeeper had to send the rider
     * away to register in the CUSTOMER app first, then come back and type the
     * address here. That is backwards for how a shop actually hires somebody -
     * you take somebody on and hand them a login - and it could never have
     * worked anyway, because nothing in this system ever set a password for a
     * rider. The roster screen made accounts by mobile number for OTP, and the
     * worker app has no OTP form. Every path ended at a rider who could not
     * sign in.
     *
     * So the shop sets both halves here, and the rider is told them. One
     * screen, no second app, no self-registration.
     *
     * WHAT IT REFUSES, and each of these is a way this could otherwise be used
     * to take an account over:
     *
     *   - An ADMIN or other staff account. Setting a password is a takeover,
     *     and DELIVERY_MANAGE is a narrower permission than the one guarding
     *     staff accounts - so whoever can manage the roster must not be able
     *     to reset the owner's password through it and sign in as them.
     *   - An account already used by a different rider, which would otherwise
     *     make findByAccountId non-unique and break that rider's next request.
     *   - A password too short to be worth having.
     *
     * The password is hashed with the same encoder as registration and never
     * logged, echoed, or returned. WorkerLoginAccountView carries only the
     * address and whether sign-in is possible.
     */
    @Transactional
    /**
     * @param actorManagesAccounts whether the person doing this already holds
     *     CUSTOMERS_MANAGE - see the staff branch below for why that, and not
     *     the target's role, is the question that matters.
     */
    public WorkerLoginAccountView linkLoginAccount(
            Long partnerId, String rawEmail, String rawPassword, boolean actorManagesAccounts) {
        DeliveryPartner partner = repository.findById(partnerId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery partner not found"));

        if (rawEmail == null || rawEmail.isBlank()) {
            throw new BadRequestException("An email address is required.");
        }
        String email = rawEmail.trim();
        String password = rawPassword == null ? "" : rawPassword.trim();

        Customer account = customerRepository.findByEmailIgnoreCase(email).orElse(null);

        if (account == null) {
            // Brand new rider. A password is not optional here - an account
            // created without one is the exact dead end this replaced.
            requireUsablePassword(password);
            account = new Customer();
            account.setFullName(partner.getName());
            account.setEmail(email);
            account.setPassword(passwordEncoder.encode(password));
            // The roster row's mobile, but only if no other account holds it -
            // the column is unique, and a rider who already shops here would
            // otherwise collide on save.
            if (partner.getMobile() != null && !partner.getMobile().isBlank()
                    && customerRepository.findByMobileNumber(partner.getMobile()).isEmpty()) {
                account.setMobileNumber(partner.getMobile());
            }
            account.setRole(Role.DELIVERY_BOY);
            account.setEnabled(true);
            account.setActive(true);
            // Verified because the shop vouched for them in person. There is no
            // email round trip to complete and no way for them to do one.
            account.setVerified(true);
        } else {
            repository.findByAccountId(account.getId()).ifPresent(existing -> {
                if (!existing.getId().equals(partner.getId())) {
                    throw new ConflictException(
                            "That address is already the login for " + existing.getName()
                                    + ". Unlink it there first.");
                }
            });

            // THE OWNER IS OFTEN ALSO THE RIDER, and the question this branch
            // has to answer is WHO IS ASKING - not whose account it is.
            //
            // The escalation to prevent is a roster-only operator setting a
            // password on the owner's account and then signing in as them.
            // DELIVERY_MANAGE is narrower than CUSTOMERS_MANAGE, so a
            // DELIVERY_MANAGER (who has the former and not the latter) must not
            // get there. But an owner who ALREADY holds CUSTOMERS_MANAGE can
            // set that password on the customer screens anyway - refusing them
            // here buys nothing and leaves a one-person shop unable to put its
            // own address on its own roster. That is the dead end that produced
            // "This login is not linked to a worker record" with no way out.
            //
            // So the gate is the actor's permission, checked on the server from
            // the authenticated role - never a flag from the request.
            //
            // Their role is left alone regardless. Promoting an administrator
            // to DELIVERY_BOY would strip every permission they have - the
            // roster screen must not be a way to demote the owner.
            if (isStaff(account.getRole())) {
                if (!RolePermissions.forRole(account.getRole())
                        .contains(AdminPermission.DELIVERY_MANAGE)) {
                    // Linking them would look like it worked and then fail at
                    // the door, because /api/worker/** admits DELIVERY_MANAGE or
                    // a delivery rider and this account is neither.
                    throw new ConflictException(
                            "That staff account does not have delivery access, so it cannot open "
                                    + "the worker app. Give it delivery permissions first, or use a "
                                    + "different address.");
                }
                if (!password.isEmpty()) {
                    if (!actorManagesAccounts) {
                        throw new ConflictException(
                                "That address belongs to a staff account, and you cannot set a "
                                        + "password on one from here. Leave the password blank to "
                                        + "link it - they sign in with the password they already "
                                        + "use.");
                    }
                    requireUsablePassword(password);
                    account.setPassword(passwordEncoder.encode(password));
                    account = customerRepository.save(account);
                }
                partner.setAccount(account);
                repository.save(partner);
                return describe(account);
            }
            // Blank password on an account that already has one means "leave
            // the password alone" - re-saving the partner should not force the
            // shopkeeper to retype it, or reset a rider's working password by
            // accident.
            boolean hasPassword = account.getPassword() != null && !account.getPassword().isBlank();
            if (!password.isEmpty() || !hasPassword) {
                requireUsablePassword(password);
                account.setPassword(passwordEncoder.encode(password));
            }
            account.setRole(Role.DELIVERY_BOY);
            account.setEnabled(true);
            account.setActive(true);
        }

        account = customerRepository.save(account);
        // JwtFilter re-checks the role against the live row, and this cache
        // sits in front of that read - without invalidating it the promotion
        // would not take effect until the entry expired.
        accountStatusService.invalidate(account.getId());

        partner.setAccount(account);
        repository.save(partner);

        return describe(account);
    }

    /** Short enough to guess is not a credential. Matched to registration. */
    private void requireUsablePassword(String password) {
        if (password == null || password.trim().length() < MIN_WORKER_PASSWORD_LENGTH) {
            throw new BadRequestException(
                    "Set a password of at least " + MIN_WORKER_PASSWORD_LENGTH
                            + " characters for this rider.");
        }
    }

    private static final int MIN_WORKER_PASSWORD_LENGTH = 8;

    /**
     * Anything that is not a shopper or a rider.
     *
     * Written as "not one of two" rather than "is one of seven" on purpose: a
     * role added later is treated as privileged until somebody decides
     * otherwise, which fails in the safe direction.
     */
    private static boolean isStaff(Role role) {
        return role != null && role != Role.CUSTOMER && role != Role.DELIVERY_BOY;
    }

    /**
     * Detaches the login account.
     *
     * Demotes DELIVERY_BOY back to CUSTOMER, because an account left with that
     * role but no roster row can still reach the worker endpoints - it just
     * gets "no delivery partner profile" from every one of them. Any other
     * role is left alone: this is unlinking a rider, not a place to change
     * what an ADMIN or a MANAGER is.
     */
    @Transactional
    public WorkerLoginAccountView unlinkLoginAccount(Long partnerId) {
        DeliveryPartner partner = repository.findById(partnerId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery partner not found"));

        Customer account = partner.getAccount();
        partner.setAccount(null);
        repository.save(partner);

        if (account != null && account.getRole() == Role.DELIVERY_BOY) {
            account.setRole(Role.CUSTOMER);
            customerRepository.save(account);
            accountStatusService.invalidate(account.getId());
        }
        return WorkerLoginAccountView.none();
    }

    private WorkerLoginAccountView describe(Customer account) {
        if (account == null) {
            return WorkerLoginAccountView.none();
        }
        boolean hasPassword = account.getPassword() != null && !account.getPassword().isBlank();
        boolean hasEmail = account.getEmail() != null && !account.getEmail().isBlank();
        return new WorkerLoginAccountView(true, account.getEmail(), hasEmail && hasPassword);
    }

    public List<DeliveryPartner> getAll(Pageable pageable) {
        return repository.findAll(pageable).getContent();
    }

    public List<DeliveryPartner> getAvailablePartners() {
        return repository.findByAvailable(true, PageRequests.of(0, PageRequests.MAX_PAGE_SIZE)).getContent();
    }

    public DeliveryPartner update(DeliveryPartner partner) {
        return repository.save(partner);
    }

    /** Resolves a logged-in Customer (role=DELIVERY_BOY) back to their roster record - throws if not linked to one. */
    public DeliveryPartner getByAccountIdOrThrow(Long customerId) {
        return repository.findByAccountId(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("No delivery partner profile linked to this account"));
    }

    /**
     * Self-service - a delivery partner setting THEIR OWN availability
     * (e.g. going off duty). Deliberately only touches the `available`
     * field on their own resolved record - unlike the bulk update() above,
     * this can never be used to edit someone else's record, since the
     * partner is resolved from the caller's own account, never a
     * client-supplied id.
     */
    public DeliveryPartner setMyAvailability(Long customerId, boolean available) {
        DeliveryPartner partner = getByAccountIdOrThrow(customerId);
        partner.setAvailable(available);
        return repository.save(partner);
    }

    /**
     * Self-service - a delivery partner's own app pushing its live GPS
     * position while on a run (called every few seconds). Same ownership
     * pattern as setMyAvailability() above: resolved from the caller's own
     * account, never a client-supplied partner id, so this can never be
     * used to spoof someone else's location.
     */
    public DeliveryPartner updateMyLocation(Long customerId, Double latitude, Double longitude) {
        return updateMyLocation(customerId, latitude, longitude, null);
    }

    /**
     * @param accuracyMeters the phone's own confidence in the fix, or null when
     *                       it did not say. Fixes vaguer than the configured
     *                       ceiling are refused rather than stored.
     */
    public DeliveryPartner updateMyLocation(Long customerId, Double latitude, Double longitude,
                                            Double accuracyMeters) {
        DeliveryPartner partner = getByAccountIdOrThrow(customerId);

        // NaN and infinity get past @DecimalMin/@DecimalMax - the comparison
        // they do is false for NaN in both directions, so neither bound
        // rejects it. Stored, it makes every distance calculation that touches
        // this partner return NaN, silently, from then on.
        if (latitude == null || longitude == null
                || !Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            throw new BadRequestException("That is not a usable position.");
        }

        // The classic no-fix sentinel. A phone that has not found satellites
        // yet reports exactly (0, 0), which is a real coordinate in the Gulf
        // of Guinea and will be drawn on the map as confidently as any other.
        // Refusing it means the admin screen keeps showing the last position
        // that was real, with its own timestamp, which is the honest answer.
        if (latitude == 0.0d && longitude == 0.0d) {
            throw new BadRequestException(
                    "The phone has not got a position fix yet. Nothing was recorded.");
        }

        if (accuracyMeters != null && Double.isFinite(accuracyMeters)
                && accuracyMeters > maxLocationAccuracyMeters) {
            // A cell-tower guess, not a position. Kilometres of uncertainty
            // rendered as a pin looks exactly like a real fix.
            throw new BadRequestException(
                    "That position is only accurate to about " + Math.round(accuracyMeters)
                            + " m, which is too vague to record.");
        }

        partner.setCurrentLatitude(latitude);
        partner.setCurrentLongitude(longitude);
        // SERVER TIME, never the phone's. The timestamp is what tells an
        // administrator whether a pin is current or an hour old, and a device
        // clock is one more untrusted field - a phone with a wrong date would
        // make a stale position look fresh, or a fresh one look stale.
        partner.setLocationUpdatedAt(LocalDateTime.now());
        return repository.save(partner);
    }

    /**
     * Same load-balancing as getLeastLoadedAvailablePartner(), but prefers a
     * partner whose vehicleType matches (e.g. "PICKUP" for a bulk order).
     * If no available partner of that vehicle type exists, falls back to the
     * least-loaded available partner of ANY type - a bulk order should still
     * go out on a bike rather than not going out at all.
     */
    public DeliveryPartner getLeastLoadedAvailablePartner(String preferredVehicleType) {
        List<DeliveryPartner> availablePartners = repository.findByAvailable(true);

        if (availablePartners.isEmpty()) {
            throw new ResourceNotFoundException("No delivery partners are currently available");
        }

        Map<Long, Long> activeCountByPartnerId = new HashMap<>();
        for (Object[] row : deliveryRepository.countActiveDeliveriesPerPartner()) {
            activeCountByPartnerId.put((Long) row[0], (Long) row[1]);
        }

        DeliveryPartner bestMatch = pickLeastLoaded(availablePartners, activeCountByPartnerId, preferredVehicleType);
        if (bestMatch != null) {
            return bestMatch;
        }

        // No available partner of the preferred type - fall back to any available partner.
        return pickLeastLoaded(availablePartners, activeCountByPartnerId, null);
    }

    public DeliveryPartner getLeastLoadedAvailablePartner() {
        return getLeastLoadedAvailablePartner(null);
    }

    private DeliveryPartner pickLeastLoaded(
            List<DeliveryPartner> partners, Map<Long, Long> activeCountByPartnerId, String vehicleTypeFilter) {

        DeliveryPartner best = null;
        long bestCount = Long.MAX_VALUE;

        for (DeliveryPartner partner : partners) {
            if (vehicleTypeFilter != null && !vehicleTypeFilter.equalsIgnoreCase(partner.getVehicleType())) {
                continue;
            }

            long count = activeCountByPartnerId.getOrDefault(partner.getId(), 0L);
            if (count < bestCount) {
                best = partner;
                bestCount = count;
            }
        }

        return best;
    }
}