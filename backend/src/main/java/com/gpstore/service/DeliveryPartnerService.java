package com.gpstore.service;

import com.gpstore.entity.Customer;
import com.gpstore.entity.DeliveryPartner;
import com.gpstore.entity.Role;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.DeliveryPartnerRepository;
import com.gpstore.repository.DeliveryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DeliveryPartnerService {

    private final DeliveryPartnerRepository repository;
    private final DeliveryRepository deliveryRepository;
    private final CustomerRepository customerRepository;

    public DeliveryPartnerService(
            DeliveryPartnerRepository repository,
            DeliveryRepository deliveryRepository,
            CustomerRepository customerRepository) {
        this.repository = repository;
        this.deliveryRepository = deliveryRepository;
        this.customerRepository = customerRepository;
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

            saved.setAccount(account);
            saved = repository.save(saved);
        }

        return saved;
    }

    public List<DeliveryPartner> getAll() {
        return repository.findAll();
    }

    public List<DeliveryPartner> getAvailablePartners() {
        return repository.findByAvailable(true);
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