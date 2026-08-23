package com.gpstore.territory;

import com.gpstore.entity.Address;
import com.gpstore.entity.Delivery;
import com.gpstore.repository.DeliveryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the loose orders sitting in one territory into a route worth riding.
 *
 * WHY THIS ONLY WORKS PER TERRITORY. Five drops scattered across the whole
 * service area cannot be sequenced into anything useful - the nearest one is
 * still twenty minutes away. Five drops inside Z7A are three streets, and the
 * order you ride them in is the difference between forty minutes and fifteen.
 * Permanent territories are what make batching mean something, which is why
 * this class takes a subzone and not a partner.
 *
 * THE ALGORITHM is nearest-neighbour from the store, with one deliberate
 * interruption: at every step, if any remaining drop is close to breaching
 * the time it was promised, the most urgent of those is taken next instead of
 * the nearest. Nearest-neighbour alone produces a tidy loop that quietly
 * delivers somebody's order forty minutes late because their house was at the
 * far end of it, and a promise already made to a customer outranks a shorter
 * ride.
 *
 * WHAT THIS IS NOT. It is not a travelling-salesman solver and does not try
 * to be. Nearest-neighbour on five to twenty stops inside one small territory
 * lands within a few percent of optimal, needs no solver, no road graph, and
 * no external routing service - and the straight-line distances it uses are
 * an approximation anyway, so a "better" sequence computed from them is
 * frequently not better on the actual roads. The rider knows the lanes; this
 * gives them a sensible order to work in, not an instruction they must obey.
 */
@Service
public class TerritoryRouteService {

    /**
     * One stop, in the order it should be ridden.
     *
     * {@code urgent} marks a stop pulled forward because its promise was at
     * risk rather than because it was next on the loop - worth surfacing in
     * the rider's app, since a stop that appears out of geographic order looks
     * like a bug unless it says why.
     */
    public record RouteStop(Long deliveryId,
                            Long orderId,
                            String addressLine,
                            Double latitude,
                            Double longitude,
                            LocalDateTime promisedBy,
                            boolean urgent,
                            double legKm) {
    }

    public record PlannedRoute(Long subzoneId, List<RouteStop> stops, double totalKm) {

        public int stopCount() {
            return stops.size();
        }
    }

    private final DeliveryRepository deliveryRepository;
    private final double storeLatitude;
    private final double storeLongitude;
    private final long urgencyWindowMinutes;

    public TerritoryRouteService(
            DeliveryRepository deliveryRepository,
            @Value("${store.latitude}") double storeLatitude,
            @Value("${store.longitude}") double storeLongitude,
            @Value("${territory.route-urgency-window-minutes:20}") long urgencyWindowMinutes) {
        this.deliveryRepository = deliveryRepository;
        this.storeLatitude = storeLatitude;
        this.storeLongitude = storeLongitude;
        this.urgencyWindowMinutes = urgencyWindowMinutes;
    }

    /** Plans a route over everything currently waiting in one territory. */
    @Transactional(readOnly = true)
    public PlannedRoute planRoute(Long subzoneId) {
        return plan(subzoneId, deliveryRepository.findBatchableBySubzoneId(subzoneId), LocalDateTime.now());
    }

    /**
     * The planner proper, taking its deliveries and its clock as arguments.
     *
     * Split out from {@link #planRoute} so the sequencing can be tested
     * against a fixed set of stops and a fixed "now" - a route planner whose
     * output depends on the wall clock is one nobody can write an assertion
     * about.
     */
    PlannedRoute plan(Long subzoneId, List<Delivery> deliveries, LocalDateTime now) {
        List<Delivery> remaining = new ArrayList<>();
        for (Delivery delivery : deliveries) {
            if (coordinatesOf(delivery) != null) {
                remaining.add(delivery);
            }
            // A drop with no coordinates cannot be sequenced. It is left out
            // of the route rather than pinned to the start or the end, both of
            // which would be a guess presented as a plan. It is still a real
            // assigned delivery and still shows in the rider's list.
        }

        List<RouteStop> stops = new ArrayList<>();
        double totalKm = 0;
        double currentLat = storeLatitude;
        double currentLng = storeLongitude;

        while (!remaining.isEmpty()) {
            int chosen = chooseNext(remaining, currentLat, currentLng, now);
            Delivery next = remaining.remove(chosen);

            double[] point = coordinatesOf(next);
            double legKm = TerritoryDispatchService.haversineKm(currentLat, currentLng, point[0], point[1]);
            totalKm += legKm;

            stops.add(new RouteStop(
                    next.getId(),
                    next.getOrder() == null ? null : next.getOrder().getId(),
                    describe(next),
                    point[0],
                    point[1],
                    next.getEstimatedDeliveryTime(),
                    isUrgent(next, now),
                    round1(legKm)));

            currentLat = point[0];
            currentLng = point[1];
        }

        return new PlannedRoute(subzoneId, stops, round1(totalKm));
    }

    /**
     * The next stop: the most urgent one if anything is close to breaching its
     * promise, otherwise the nearest.
     *
     * Urgency is checked across ALL remaining stops rather than only the
     * nearby ones, on purpose. The whole failure this guards against is a
     * distant drop whose deadline expires while the loop tidily works its way
     * around to it.
     */
    private int chooseNext(List<Delivery> remaining, double fromLat, double fromLng, LocalDateTime now) {
        int mostUrgent = -1;
        LocalDateTime earliestDeadline = null;

        for (int i = 0; i < remaining.size(); i++) {
            Delivery delivery = remaining.get(i);
            if (!isUrgent(delivery, now)) {
                continue;
            }
            LocalDateTime deadline = delivery.getEstimatedDeliveryTime();
            if (earliestDeadline == null || deadline.isBefore(earliestDeadline)) {
                earliestDeadline = deadline;
                mostUrgent = i;
            }
        }

        if (mostUrgent >= 0) {
            return mostUrgent;
        }

        int nearest = 0;
        double bestKm = Double.MAX_VALUE;
        for (int i = 0; i < remaining.size(); i++) {
            double[] point = coordinatesOf(remaining.get(i));
            double km = TerritoryDispatchService.haversineKm(fromLat, fromLng, point[0], point[1]);
            if (km < bestKm) {
                bestKm = km;
                nearest = i;
            }
        }
        return nearest;
    }

    private boolean isUrgent(Delivery delivery, LocalDateTime now) {
        LocalDateTime promised = delivery.getEstimatedDeliveryTime();
        if (promised == null) {
            return false;
        }
        // Already past its promise counts as urgent too - it is the most
        // urgent thing there is, and the customer is already waiting.
        return Duration.between(now, promised).toMinutes() <= urgencyWindowMinutes;
    }

    private double[] coordinatesOf(Delivery delivery) {
        if (delivery.getOrder() == null || delivery.getOrder().getAddress() == null) {
            return null;
        }
        Address address = delivery.getOrder().getAddress();
        if (address.getLatitude() == null || address.getLongitude() == null) {
            return null;
        }
        return new double[]{address.getLatitude(), address.getLongitude()};
    }

    private String describe(Delivery delivery) {
        if (delivery.getOrder() == null || delivery.getOrder().getAddress() == null) {
            return null;
        }
        Address address = delivery.getOrder().getAddress();
        StringBuilder line = new StringBuilder();
        appendIfPresent(line, address.getHouseNo());
        appendIfPresent(line, address.getArea());
        appendIfPresent(line, address.getLandmark());
        return line.isEmpty() ? null : line.toString();
    }

    private static void appendIfPresent(StringBuilder line, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        if (!line.isEmpty()) {
            line.append(", ");
        }
        line.append(part.trim());
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
