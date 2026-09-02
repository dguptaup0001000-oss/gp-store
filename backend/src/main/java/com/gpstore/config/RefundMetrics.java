package com.gpstore.config;

import com.gpstore.repository.PaymentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Refunds in flight as Prometheus gauges, on the same admin-only
 * {@code /v1/actuator/prometheus} endpoint the backup gauges use.
 *
 * WHY A COUNT IS NOT ENOUGH ON ITS OWN. Refunds in flight is a healthy
 * number on a busy afternoon and an alarm on a quiet Tuesday - the count
 * alone cannot tell those apart. The age of the oldest one can: a refund
 * that has been with the provider for four days is stuck whatever the
 * count says, and that is the number worth alerting on.
 *
 * NOT ON /actuator/health, deliberately, and for the same reason the
 * backup gauges are not: a stuck refund needs a person, but it must never
 * take the shop off Traefik. Customers can still buy while one refund is
 * being chased.
 */
@Configuration
public class RefundMetrics {

    public RefundMetrics(MeterRegistry registry, PaymentRepository paymentRepository) {

        Gauge.builder("gpstore.refunds.awaiting_provider", paymentRepository,
                        PaymentRepository::countRefundsAwaitingProvider)
                .description("Refunds sent to the payment provider that have not been confirmed landed")
                .register(registry);

        Gauge.builder("gpstore.refunds.oldest_awaiting_seconds", paymentRepository, repo -> {
                    LocalDateTime oldest = repo.oldestRefundAwaitingProvider();
                    // Zero when nothing is in flight, which reads correctly on
                    // a graph: the line sits on the floor and climbs only while
                    // something is genuinely waiting.
                    if (oldest == null) {
                        return 0.0;
                    }
                    return Math.max(0, Duration.between(oldest, LocalDateTime.now()).toSeconds());
                })
                .description("Age of the oldest refund still awaiting confirmation from the provider")
                .register(registry);
    }
}
