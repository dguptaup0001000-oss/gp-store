package com.gpstore.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whose data the current unit of work may touch.
 *
 * THE FAILURE THESE PREVENT is not a wrong answer, it is a plausible one. A
 * scope that quietly defaults to "everything" when nobody set it turns every
 * forgotten call site into a query that reads every shop's orders and returns
 * 200 OK. Nothing looks broken until a shopkeeper sees a rival's takings.
 *
 * So: no scope is an error, the unscoped case has to be asked for by name,
 * and a scope never survives the thread that set it.
 */
@DisplayName("Tenant scope")
class TenantScopeTest {

    @AfterEach
    void clearScope() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("reading a scope nobody set is an error, not a default")
    void anUnsetScopeFails() {
        assertFalse(TenantContext.isSet());
        assertNull(TenantContext.current());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, TenantContext::require);
        assertTrue(thrown.getMessage().contains("must say whose data"),
                "the message has to tell the next person what to do, not just that it broke");
    }

    @Test
    @DisplayName("acting across every shop has to be asked for by name")
    void platformScopeIsExplicit() {
        // A background sweep written as TenantScope.platform() says what it
        // is doing. The same sweep with no scope at all would read
        // identically to one that had simply forgotten.
        TenantScope platform = TenantScope.platform();
        assertTrue(platform.isPlatform());
        assertFalse(platform.isSingleShop());
        assertNull(platform.shopId());

        assertThrows(IllegalStateException.class, platform::requireShopId,
                "work that belongs to one shop must not silently accept the platform scope");
    }

    @Test
    @DisplayName("a shop scope cannot be built without a shop")
    void aShopScopeNeedsAShop() {
        // The tempting shortcut - ofShop(null) meaning "all of them" - is how
        // the platform scope stops being deliberate.
        assertThrows(IllegalArgumentException.class, () -> TenantScope.ofShop(null));

        TenantScope shop = TenantScope.ofShop(7L);
        assertEquals(7L, shop.requireShopId());
        assertTrue(shop.isSingleShop());
        assertFalse(shop.isPlatform());
    }

    @Test
    @DisplayName("set(null) is refused - clearing is a different intention")
    void setNullIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> TenantContext.set(null));
    }

    @Test
    @DisplayName("runWithin restores what was there, so a nested call cannot strip it")
    void nestedScopesRestore() {
        // A platform sweep that dips into one shop's data must come back out
        // to the platform scope, not to nothing - otherwise the rest of the
        // sweep runs unscoped and require() starts throwing halfway through.
        TenantContext.set(TenantScope.platform());

        String result = TenantContext.runWithin(TenantScope.ofShop(3L), () -> {
            assertEquals(3L, TenantContext.require().requireShopId());
            return "done";
        });

        assertEquals("done", result);
        assertTrue(TenantContext.require().isPlatform(), "the outer scope must come back");
    }

    @Test
    @DisplayName("runWithin clears when there was nothing before")
    void runWithinLeavesNoResidue() {
        assertFalse(TenantContext.isSet());
        TenantContext.runWithin(TenantScope.ofShop(9L), () -> assertTrue(TenantContext.isSet()));
        assertFalse(TenantContext.isSet(),
                "a scope left behind is the scope the next piece of work starts with");
    }

    @Test
    @DisplayName("a scope survives an exception no better than it survives success")
    void runWithinRestoresAfterFailure() {
        TenantContext.set(TenantScope.ofShop(1L));

        assertThrows(IllegalStateException.class, () ->
                TenantContext.runWithin(TenantScope.ofShop(2L), () -> {
                    throw new IllegalStateException("boom");
                }));

        assertEquals(1L, TenantContext.require().requireShopId(),
                "an exception must not leave the wrong shop on the thread");
    }

    @Test
    @DisplayName("one thread's shop is invisible to another")
    void scopeDoesNotLeakAcrossThreads() throws Exception {
        // NOT InheritableThreadLocal, deliberately. Inheriting would hand the
        // current shop to every thread a request spawns, including pool
        // threads that outlive it and then serve somebody else.
        TenantContext.set(TenantScope.ofShop(42L));

        AtomicReference<TenantScope> seenElsewhere = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> {
                seenElsewhere.set(TenantContext.current());
                done.countDown();
            });
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertNull(seenElsewhere.get(),
                "another thread must not inherit this request's shop");
        assertEquals(42L, TenantContext.require().requireShopId());
    }

    @Test
    @DisplayName("two scopes for the same shop are the same scope")
    void equality() {
        assertEquals(TenantScope.ofShop(5L), TenantScope.ofShop(5L));
        assertEquals(TenantScope.ofShop(5L).hashCode(), TenantScope.ofShop(5L).hashCode());
        assertNotEquals(TenantScope.ofShop(5L), TenantScope.ofShop(6L));
        assertEquals(TenantScope.platform(), TenantScope.platform());
        assertNotEquals(TenantScope.platform(), TenantScope.ofShop(1L));
    }
}
