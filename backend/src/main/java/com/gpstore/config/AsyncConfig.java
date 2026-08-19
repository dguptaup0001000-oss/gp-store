package com.gpstore.config;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    private ThreadPoolExecutor orderSideEffectsExecutor;

    /**
     * Backs OrderService's post-order side effects (push notification, audit
     * log, invoice generation, delivery auto-assignment) and
     * NotificationService's broadcast persistence. Those were already
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
     *
     * Explicitly NOT Executors.newFixedThreadPool(4) - that factory hands
     * back a ThreadPoolExecutor backed by an unbounded LinkedBlockingQueue,
     * so if this pool's 4 workers ever fall behind (a slow FCM/invoice call,
     * a burst of orders), submitted tasks would queue up with no limit at
     * all - unbounded memory growth under sustained load, exactly what a
     * bounded-queue pool is supposed to prevent. Built manually instead with
     * a real capacity and CallerRunsPolicy: once the queue is full, the next
     * submission runs on the CALLING thread (the request thread, right after
     * commit) instead of being silently dropped or queued forever. That's
     * deliberate backpressure - invoice generation is genuinely
     * business-critical (GST/accounting), so under sustained overload this
     * pool should slow down order-placement throughput rather than either
     * losing invoices or growing without bound. The individual submitted
     * tasks (see OrderService.placeOrder's guardedAfterCommitWork) already
     * catch and log their own exceptions either way, so a task running
     * inline under this policy fails exactly as safely as one running on a
     * pool thread.
     */
    @Bean(destroyMethod = "")
    public ExecutorService orderSideEffectsExecutor() {
        orderSideEffectsExecutor = new ThreadPoolExecutor(
                4, 4,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(200),
                new ThreadPoolExecutor.CallerRunsPolicy());
        return orderSideEffectsExecutor;
    }

    /**
     * Explicit graceful shutdown rather than letting the JVM kill worker
     * threads mid-task on redeploy - gives already-submitted (but not yet
     * run) side-effect work, like invoice generation, a real window to
     * finish instead of being silently discarded. destroyMethod="" on the
     * @Bean above stops Spring from ALSO calling the bare shutdown() through
     * its own inferred-destroy-method lifecycle (ExecutorService.shutdown()
     * happens to match the method name Spring looks for by default) - this
     * @PreDestroy is the one path that actually runs, and it waits before
     * giving up rather than just firing shutdown() and moving on.
     */
    @PreDestroy
    public void shutdownOrderSideEffectsExecutor() {
        if (orderSideEffectsExecutor == null) {
            return;
        }
        orderSideEffectsExecutor.shutdown();
        try {
            if (!orderSideEffectsExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("orderSideEffectsExecutor did not finish pending work within 10s of shutdown - forcing it now; any task still queued at this point is lost.");
                orderSideEffectsExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            orderSideEffectsExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
