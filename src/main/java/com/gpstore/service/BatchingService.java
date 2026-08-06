package com.gpstore.service;

import com.gpstore.entity.DeliveryBatch;
import com.gpstore.entity.DeliveryPartner;
import com.gpstore.entity.Order;
import com.gpstore.enums.OrderStatus;
import com.gpstore.repository.DeliveryBatchRepository;
import com.gpstore.repository.DeliveryPartnerRepository;
import com.gpstore.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class BatchingService {

    private static final int MAX_WAIT_MINUTES = 45;

    @Autowired private OrderRepository orderRepository;
    @Autowired private DeliveryBatchRepository batchRepository;
    @Autowired private DeliveryPartnerRepository partnerRepository;

    @Scheduled(fixedDelay = 300000)
    @Transactional
    public void formBatches() {
        List<Order> pending = orderRepository.findByOrderStatus(OrderStatus.PENDING_CONFIRMATION)
                .stream()
                .filter(o -> o.getAddress() != null && o.getAddress().getDeliverySubzone() != null)
                .sorted(Comparator.comparing(Order::getOrderDate))
                .collect(Collectors.toList());

        Map<String, List<Order>> bySubzone = pending.stream()
                .collect(Collectors.groupingBy(o -> o.getAddress().getDeliverySubzone()));

        for (Map.Entry<String, List<Order>> entry : bySubzone.entrySet()) {
            String subzone = entry.getKey();
            List<Order> orders = entry.getValue();

            boolean full = orders.size() >= DeliveryBatch.MAX_ORDERS_PER_BATCH;
            boolean stale = !orders.isEmpty()
                    && orders.get(0).getOrderDate()
                            .isBefore(LocalDateTime.now().minusMinutes(MAX_WAIT_MINUTES));

            if (full || stale) {
                int take = Math.min(orders.size(), DeliveryBatch.MAX_ORDERS_PER_BATCH);
                createBatch(subzone, orders.subList(0, take));
            }
        }
    }

    private void createBatch(String subzone, List<Order> orders) {
        DeliveryBatch batch = new DeliveryBatch();
        batch.setBatchNumber("B" + System.currentTimeMillis());
        batch.setArea(subzone);
        batch.setStatus("OPEN");
        batch.setCreatedAt(LocalDateTime.now());
        batch.setActive(true);

        Optional<DeliveryPartner> partner = selectPartner(subzone);
        if (partner.isPresent()) {
            batch.setDeliveryPartner(partner.get());
            batch.setStatus("FULL");
        }

        batchRepository.save(batch);
    }

    private Optional<DeliveryPartner> selectPartner(String subzone) {
        List<DeliveryPartner> candidates = partnerRepository.findAll().stream()
                .filter(p -> Boolean.TRUE.equals(p.getAvailable()))
                .filter(p -> Boolean.TRUE.equals(p.getActive()))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        return candidates.stream().max(Comparator.comparingInt(p -> score(p, subzone)));
    }

    private int score(DeliveryPartner partner, String subzone) {
        long openBatches = batchRepository.findAll().stream()
                .filter(b -> b.getDeliveryPartner() != null
                        && b.getDeliveryPartner().getId().equals(partner.getId())
                        && !"COMPLETED".equals(b.getStatus()))
                .count();

        long zoneExperience = batchRepository.findAll().stream()
                .filter(b -> b.getDeliveryPartner() != null
                        && b.getDeliveryPartner().getId().equals(partner.getId())
                        && subzone.equals(b.getArea()))
                .count();

        int loadScore = (int) Math.max(0, 40 - (openBatches * 20));
        int affinityScore = (int) Math.min(30, zoneExperience * 5);
        return loadScore + affinityScore;
    }
}
