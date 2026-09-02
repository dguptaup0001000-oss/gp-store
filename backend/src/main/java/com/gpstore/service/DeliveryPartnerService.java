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

    // The worker's login used to be attached here, by pointing a roster row
    // at a Customer account and setting that account's password. It now lives
    // on the roster row itself - see WorkerAdminService. One place to set a
    // worker's credentials instead of two, which is what made the old one so
    // hard to get right.

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
     * The roster row for a worker-app session.
     *
     * THE ID COMES FROM THE TOKEN. A worker session carries its own roster id,
     * so this is a primary-key read rather than a translation through an
     * account link that could be missing - which is what used to strand a
     * signed-in rider with "not linked to a worker record".
     *
     * Still filtered on deletedAt: a removed worker keeps their row so the
     * shop's delivery history stays readable, and must not keep working.
     */
    public DeliveryPartner getLiveWorkerOrThrow(Long workerId) {
        if (workerId == null) {
            throw new BadRequestException(
                    "Sign in with a worker login to use the worker app.");
        }
        return repository.findById(workerId)
                .filter(worker -> worker.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "This worker account is no longer on the roster."));
    }

    /**
     * Self-service - a delivery partner setting THEIR OWN availability
     * (e.g. going off duty). Deliberately only touches the `available`
     * field on their own resolved record - unlike the bulk update() above,
     * this can never be used to edit someone else's record, since the
     * partner is resolved from the caller's own token, never a
     * client-supplied id.
     */
    public DeliveryPartner setMyAvailability(Long workerId, boolean available) {
        DeliveryPartner partner = getLiveWorkerOrThrow(workerId);
        partner.setAvailable(available);
        return repository.save(partner);
    }

    /**
     * Self-service - a delivery partner's own app pushing its live GPS
     * position while on a run (called every few seconds). Same ownership
     * pattern as setMyAvailability() above: resolved from the caller's own
     * token, never a client-supplied partner id, so this can never be
     * used to spoof someone else's location.
     */
    public DeliveryPartner updateMyLocation(Long workerId, Double latitude, Double longitude) {
        return updateMyLocation(workerId, latitude, longitude, null);
    }

    /**
     * @param accuracyMeters the phone's own confidence in the fix, or null when
     *                       it did not say. Fixes vaguer than the configured
     *                       ceiling are refused rather than stored.
     */
    public DeliveryPartner updateMyLocation(Long workerId, Double latitude, Double longitude,
                                            Double accuracyMeters) {
        DeliveryPartner partner = getLiveWorkerOrThrow(workerId);

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