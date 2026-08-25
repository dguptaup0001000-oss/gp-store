package com.gpstore.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ExecutorService;

/**
 * Runs non-critical work AFTER the current transaction commits, on a pool
 * thread rather than the request thread.
 *
 * Why both halves matter:
 *
 * - AFTER COMMIT, because the work must not be able to roll back the
 *   business operation that triggered it, and because anything reading
 *   the row needs it to actually exist first.
 * - OFF THE REQUEST THREAD, because
 *   TransactionSynchronization.afterCommit() still runs synchronously on
 *   the SAME thread - just after the commit instead of before it. On its
 *   own it does NOT stop the caller waiting. Push notification delivery
 *   goes through FirebaseMessaging.send(), a blocking network call to
 *   Google; leaving that in the request path made every assignment and
 *   status change hold a Hikari connection for the Firebase RTT.
 *
 * Nothing here may throw into the caller: by this point the business
 * write is already committed. Throwable, not Exception, deliberately -
 * narrowing it to Exception would leave Errors able to turn a successful
 * operation into an error response.
 *
 * Use this only for work that is safe to LOSE on a crash. Anything
 * business-critical belongs in the outbox instead.
 */
@Component
public class AfterCommitExecutor {

    private static final Logger log = LoggerFactory.getLogger(AfterCommitExecutor.class);

    private final ExecutorService orderSideEffectsExecutor;

    public AfterCommitExecutor(ExecutorService orderSideEffectsExecutor) {
        this.orderSideEffectsExecutor = orderSideEffectsExecutor;
    }

    public void runAfterCommit(String description, Long entityId, Runnable work) {
        Runnable guarded = () -> {
            try {
                work.run();
            } catch (Throwable t) {
                log.error("{} failed for id {} - the committed work itself is unaffected",
                        description, entityId, t);
            }
        };

        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                orderSideEffectsExecutor.submit(guarded);
                            }
                        });
            } else {
                orderSideEffectsExecutor.submit(guarded);
            }
        } catch (Throwable t) {
            log.error("Failed to register/run {} for id {} - the committed work itself is unaffected",
                    description, entityId, t);
        }
    }
}
