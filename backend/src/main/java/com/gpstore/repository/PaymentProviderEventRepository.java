package com.gpstore.repository;

import com.gpstore.entity.PaymentProviderEvent;
import com.gpstore.enums.PaymentProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentProviderEventRepository extends JpaRepository<PaymentProviderEvent, Long> {

    Optional<PaymentProviderEvent> findByProviderAndEventId(PaymentProvider provider, String eventId);

    /** Reconciliation: everything the gateway ever said about one order. */
    List<PaymentProviderEvent> findByProviderOrderIdOrderByReceivedAtAsc(String providerOrderId);
}
