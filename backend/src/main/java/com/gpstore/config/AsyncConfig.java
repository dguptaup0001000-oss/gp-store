package com.gpstore.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AsyncConfig {

    /**
     * Backs OrderService's post-order side effects (push notification, audit
     * log, invoice generation, delivery auto-assignment). Those were already
     * deferred to run after the order transaction commits, but Spring's
     * TransactionSynchronization.afterCommit() still runs synchronously on
     * the SAME request thread - just after the DB commit, not before it -
     * so the customer's "order placed" response was still waiting on an FCM
     * push call, invoice generation, and a delivery-partner query before it
     * could return. Measured at ~6-7s of real placeOrder() latency in
     * production. Submitting that work to this small pool instead lets the
     * HTTP response return the moment the DB commit succeeds.
     *
     * Bounded and small (not unbounded/cached) since this runs on a 0.5 CPU
     * instance and the work is I/O-bound (network calls, a few queries),
     * not CPU-heavy - a handful of concurrent orders is the realistic
     * ceiling right now, not hundreds.
     */
    @Bean
    public ExecutorService orderSideEffectsExecutor() {
        return Executors.newFixedThreadPool(4);
    }
}
