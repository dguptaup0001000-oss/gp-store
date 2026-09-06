package com.gpstore.platform;

import com.gpstore.config.AfterCommitExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * No background thread touches data without first saying whose it is.
 *
 * THE SAME BUG TWICE, AND IT COST THE WHOLE MARKETPLACE ONCE. A tenant scope
 * is a ThreadLocal. A scheduled job and a post-commit callback both run on a
 * pool thread, so neither inherits one - and an absent scope behaves ALMOST
 * exactly like the platform scope: the filter is disabled, @PostLoad checks
 * nothing, and reads therefore span every shop, which for a sweep is correct.
 *
 * The one place they differ is inserting a shop-owned row, and there the
 * difference is total: with no scope and more than one shop there is no answer
 * and the insert throws. That is precisely how the outbox worker failed in
 * MULTI_SHOP_PRODUCTION - no invoice and no rider for any order in the market,
 * with nothing to show for it but a dead-lettered event (Slice 10, V53).
 *
 * So "deliberately spans every shop" and "nobody set a scope" looked identical
 * until they didn't. These tests are about keeping them distinguishable:
 *
 *   a scheduled sweep gets the PLATFORM scope, by name, from the one place
 *   every scheduled task passes through - so a new job is covered without
 *   anyone remembering, which matters because three of the nine existing jobs
 *   already hid from a grep by writing @Scheduled fully qualified;
 *
 *   a post-commit continuation gets the REQUEST'S scope, captured at submit.
 *   Sending an order's notification is part of placing that order and belongs
 *   to that order's shop; widening it to the platform would let a continuation
 *   write into a shop the request had no business in.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@DisplayName("Background work says whose data it may touch")
class BackgroundWorkIsScopedTest {

    @Autowired private TaskScheduler taskScheduler;
    @Autowired private AfterCommitExecutor afterCommitExecutor;

    /**
     * Every @Scheduled method in the application, and what scope it runs in.
     *
     * TWELVE, NOT NINE. Two separate text searches for "@Scheduled" found nine
     * of them; the reflective scan below found three more, including the
     * stuck-refund reconciler - which is money - and the crash-report
     * retention sweep. An audit of "every background path" done by grep looks
     * complete and is three quarters of the way there, which is why this list
     * is built from the annotations themselves.
     *
     * ALL OF THEM ARE PLATFORM-WIDE TODAY, and each is listed rather than
     * counted so that adding a job is a moment where somebody has to answer
     * the question. The default the decorator gives is the safe one for a
     * sweep; the entry that will one day matter is a job that should run PER
     * SHOP, because that one needs its own scope per iteration the way
     * OutboxWorker.drain does, and the platform default would be silently
     * wrong for it.
     */
    private static final Set<String> SCHEDULED_JOBS_REVIEWED = Set.of(
            // Drains durable work. Platform-wide to FIND the events; enters
            // each event's own shop scope to DO the work - the nesting this
            // whole mechanism has to support.
            "OutboxWorker.drain",
            // Deletes processed events. Touches nothing shop-owned.
            "OutboxWorker.purgeOldProcessedEvents",
            // Expires abandoned gateway payments and restores their stock.
            // Spans shops on purpose; the inventory row it credits is named
            // from the ORDER being restored, never from the scope - see
            // InventoryRepository.findByProductVariantIdAndShopIdForUpdate.
            "PaymentService.expireStalePendingUpiPayments",
            // Flags deliveries past their promise. Updates shop-owned rows it
            // has already loaded; inserts nothing.
            "DeliveryService.flagLateDeliveries",
            // Housekeeping on tables that belong to no shop.
            "IdempotencyRetentionService.cleanupExpiredRecords",
            "OtpService.cleanUpExpiredOtps",
            "R2StagingSweepService.sweepExpired",
            // Asks the provider what happened to refunds it accepted and never
            // confirmed. Money, and platform-wide on purpose - a stuck refund
            // is stuck whichever shop issued it. Updates the payment it
            // already loaded; creates no shop-owned row.
            "PaymentService.reconcileRefundsAwaitingProvider",
            // Retention on client crash reports (§44). Deletes only; the table
            // belongs to no shop.
            "CrashReportService.deleteOldReports",
            // In-memory search vocabularies rebuilt from the shared catalogue,
            // which has no shop by design (§10).
            "SynonymDictionary.refresh",
            "BrandVocabulary.refresh");

    @AfterEach
    void tidyUp() {
        TenantContext.clear();
    }

    // ------------------------------------------------------- the decorator

    @Test
    @DisplayName("a scheduled task really is given a scope by the scheduler the app uses")
    void theRealSchedulerScopesItsTasks() throws Exception {
        // THROUGH THE INJECTED BEAN, not through the decorator directly. What
        // has to be true is that the customizer reached the scheduler Spring
        // Boot actually built - a decorator nobody applied would pass every
        // unit test and change nothing at runtime.
        AtomicReference<TenantScope> seen = new AtomicReference<>();
        AtomicReference<Boolean> wasSet = new AtomicReference<>();
        CountDownLatch ran = new CountDownLatch(1);

        taskScheduler.schedule(() -> {
            wasSet.set(TenantContext.isSet());
            seen.set(TenantContext.current());
            ran.countDown();
        }, Instant.now());

        assertTrue(ran.await(10, TimeUnit.SECONDS), "the scheduled probe never ran");
        assertEquals(Boolean.TRUE, wasSet.get(),
                "a scheduled task ran with no tenant scope. Reads would span every shop - which "
                        + "for a sweep is right - but an insert of any shop-owned row would fail "
                        + "outright the moment there is more than one shop, and that is exactly "
                        + "how the outbox worker took down dispatch for the whole marketplace");
        assertNotNull(seen.get());
        assertTrue(seen.get().isPlatform(),
                "a sweep spans shops on purpose, so the scope it declares must be the platform's");
    }

    @Test
    @DisplayName("the decorator does not override a scope somebody already chose")
    void anExistingScopeIsLeftAlone() {
        AtomicReference<Long> seen = new AtomicReference<>();
        Runnable decorated = com.gpstore.platform.BackgroundWorkScope.platformWide()
                .decorate(() -> seen.set(TenantContext.require().shopId()));

        TenantContext.runWithin(TenantScope.ofShop(4242L), decorated);

        assertEquals(4242L, seen.get(),
                "a task invoked inside a scope somebody deliberately set must keep it - widening "
                        + "it to the platform would quietly turn a shop's work into everyone's");
    }

    // --------------------------------------------- the post-commit carrier

    @Test
    @DisplayName("post-commit work runs in the shop the request was for, not the platform")
    void aContinuationKeepsItsRequestsShop() throws Exception {
        AtomicReference<TenantScope> seen = new AtomicReference<>();
        CountDownLatch ran = new CountDownLatch(1);

        TenantContext.runWithin(TenantScope.ofShop(77L), () -> {
            afterCommitExecutor.runAfterCommit("scope probe", 1L, () -> {
                seen.set(TenantContext.current());
                ran.countDown();
            });
            return null;
        });

        assertTrue(ran.await(10, TimeUnit.SECONDS), "the post-commit probe never ran");
        assertNotNull(seen.get(), "post-commit work ran with no scope at all");
        assertEquals(77L, seen.get().shopId(),
                "sending an order's notification is part of placing THAT order, in THAT shop. "
                        + "Running it platform-wide would let a continuation write into a shop "
                        + "its own request had no business in");
    }

    @Test
    @DisplayName("a continuation queued by background work falls back to the platform, not to nothing")
    void aContinuationWithNothingToCarryStillDeclaresAScope() throws Exception {
        AtomicReference<TenantScope> seen = new AtomicReference<>();
        CountDownLatch ran = new CountDownLatch(1);

        TenantContext.clear();
        afterCommitExecutor.runAfterCommit("unscoped probe", 2L, () -> {
            seen.set(TenantContext.current());
            ran.countDown();
        });

        assertTrue(ran.await(10, TimeUnit.SECONDS), "the probe never ran");
        assertNotNull(seen.get(),
                "background work that queues more background work must still end up with a scope, "
                        + "or the second hop is the unscoped insert all over again");
        assertTrue(seen.get().isPlatform());
    }

    // ------------------------------------------------------- the structure

    @Test
    @DisplayName("a new scheduled job has to be looked at, whatever the annotation looks like")
    void everyScheduledJobHasBeenReviewed() {
        Set<String> found = new TreeSet<>();
        for (Class<?> type : componentClasses()) {
            for (Method method : type.getDeclaredMethods()) {
                // FOUND BY REFLECTION, NOT BY GREP. Three of the nine existing
                // jobs write the annotation fully qualified, so a text search
                // for "@Scheduled" misses them - which is how an audit of
                // "every scheduled path" can look complete and be two thirds
                // of the way there.
                if (method.getAnnotation(Scheduled.class) != null
                        || method.getAnnotationsByType(Scheduled.class).length > 0) {
                    found.add(type.getSimpleName() + "." + method.getName());
                }
            }
        }

        assertFalse(found.isEmpty(),
                "no scheduled jobs found at all - the scan is wrong, and a guard that finds "
                        + "nothing passes for the wrong reason");

        Set<String> unreviewed = new TreeSet<>(found);
        unreviewed.removeAll(SCHEDULED_JOBS_REVIEWED);
        assertTrue(unreviewed.isEmpty(),
                "these scheduled jobs have not been looked at. Every one runs on a pool thread "
                        + "with no scope of its own, and the decorator gives it the PLATFORM - "
                        + "right for a sweep, silently wrong for anything that should run per "
                        + "shop. Decide which it is, then list it: " + unreviewed);

        Set<String> stale = new TreeSet<>(SCHEDULED_JOBS_REVIEWED);
        stale.removeAll(found);
        assertTrue(stale.isEmpty(),
                "these entries name jobs that no longer exist; an allowlist nobody prunes is how "
                        + "an exception becomes permanent: " + stale);
    }

    private static List<Class<?>> componentClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(
                            org.springframework.beans.factory.annotation.AnnotatedBeanDefinition bd) {
                        return true;
                    }
                };
        scanner.addIncludeFilter((reader, factory) -> true);

        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.gpstore")) {
            try {
                found.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException | NoClassDefFoundError skip) {
                // A class we cannot load cannot carry a @Scheduled we can run.
            }
        }
        return found;
    }
}
