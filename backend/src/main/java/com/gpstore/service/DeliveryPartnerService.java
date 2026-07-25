package com.gpstore.service;

import com.gpstore.entity.DeliveryPartner;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.DeliveryPartnerRepository;
import com.gpstore.repository.DeliveryRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DeliveryPartnerService {

    private final DeliveryPartnerRepository repository;
    private final DeliveryRepository deliveryRepository;

    public DeliveryPartnerService(DeliveryPartnerRepository repository, DeliveryRepository deliveryRepository) {
        this.repository = repository;
        this.deliveryRepository = deliveryRepository;
    }

    public DeliveryPartner save(DeliveryPartner partner) {
        return repository.save(partner);
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