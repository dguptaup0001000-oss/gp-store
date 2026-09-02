package com.gpstore.worker;

import com.gpstore.entity.DeliveryPartner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rules that decide whether a rider gets into the worker app.
 *
 * NO SPRING AND NO DATABASE, on purpose. These are the cases that are
 * miserable to reach any other way - a pause that ran out thirty seconds ago,
 * one that has ninety minutes left, a worker removed while their app is open -
 * and they are the ones a shop actually hits. Testing them as functions means
 * they are checked on every build rather than on a phone at the counter.
 */
@DisplayName("Who may sign in to the worker app, and what they are told")
class WorkerAccessTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 2, 14, 0);

    private static DeliveryPartner ready() {
        DeliveryPartner worker = new DeliveryPartner();
        worker.setId(7L);
        worker.setName("Deepak");
        worker.setActive(true);
        worker.setLoginEmail("rider@gmail.com");
        worker.setPasswordHash("$2a$10$someBcryptHashThatIsNotChecked");
        return worker;
    }

    @Test
    @DisplayName("a live worker with a login is let in")
    void theOrdinaryCase() {
        assertTrue(WorkerAccess.check(ready(), NOW).allowed());
    }

    @Test
    @DisplayName("a deleted worker is refused, and is not told they were deleted")
    void deletedIsIndistinguishableFromUnknown() {
        DeliveryPartner worker = ready();
        worker.setDeletedAt(NOW.minusDays(1));

        WorkerAccess.Decision decision = WorkerAccess.check(worker, NOW);

        assertEquals(WorkerAccess.Verdict.DELETED, decision.verdict());
        // Same sentence as an address nobody has ever used. "That worker was
        // removed" would confirm the address exists to anyone guessing at the
        // login screen.
        assertEquals(WorkerAccess.check(null, NOW).message(), decision.message());
    }

    @Test
    @DisplayName("a worker switched off is told to ask the shop, not that their password is wrong")
    void inactiveNamesTheFix() {
        DeliveryPartner worker = ready();
        worker.setActive(false);

        WorkerAccess.Decision decision = WorkerAccess.check(worker, NOW);

        assertEquals(WorkerAccess.Verdict.INACTIVE, decision.verdict());
        assertTrue(decision.message().contains("Ask the shop"),
                "A refusal a worker cannot act on is a phone call to the shop: " + decision.message());
    }

    @Test
    @DisplayName("a roster row with no password cannot sign in")
    void noLoginYet() {
        DeliveryPartner worker = ready();
        worker.setPasswordHash(null);

        assertEquals(WorkerAccess.Verdict.NO_LOGIN, WorkerAccess.check(worker, NOW).verdict());
    }

    @Test
    @DisplayName("a pause blocks while it lasts and lifts itself when it expires")
    void suspensionEndsOnItsOwn() {
        DeliveryPartner worker = ready();
        worker.setSuspendedUntil(NOW.plusHours(1));

        assertEquals(WorkerAccess.Verdict.SUSPENDED, WorkerAccess.check(worker, NOW).verdict());

        // THE POINT OF A TIMESTAMP RATHER THAN A FLAG. Nobody has to remember
        // to clear it; one second past the deadline they are back at work.
        assertTrue(WorkerAccess.check(worker, NOW.plusHours(1).plusSeconds(1)).allowed());
    }

    @Test
    @DisplayName("a pause that has already expired is not a pause")
    void staleSuspensionIsIgnored() {
        DeliveryPartner worker = ready();
        worker.setSuspendedUntil(NOW.minusDays(3));
        worker.setSuspensionReason("Late twice last week");

        assertTrue(WorkerAccess.check(worker, NOW).allowed(),
                "A timestamp left over from last week must not read as a live bar.");
    }

    @Test
    @DisplayName("the pause message says how much longer, and why")
    void suspensionMessageIsActionable() {
        DeliveryPartner worker = ready();
        worker.setSuspendedUntil(NOW.plusHours(3));
        worker.setSuspensionReason("Bike is being repaired.");

        String message = WorkerAccess.check(worker, NOW).message();

        // A worker standing in the street wants to know whether to wait or to
        // go home. A timestamp does not answer that; "another 3 hours" does.
        assertTrue(message.contains("another 3 hours"), message);
        assertTrue(message.contains("Bike is being repaired."), message);
    }

    @Test
    @DisplayName("deletion beats every other reason, so a paused worker who leaves is simply unknown")
    void deletionIsCheckedFirst() {
        DeliveryPartner worker = ready();
        worker.setSuspendedUntil(NOW.plusHours(2));
        worker.setActive(false);
        worker.setDeletedAt(NOW.minusMinutes(1));

        assertEquals(WorkerAccess.Verdict.DELETED, WorkerAccess.check(worker, NOW).verdict(),
                "Otherwise a removed worker would be told when their pause ends.");
    }

    @Test
    @DisplayName("remaining time rounds up, never down")
    void remainingTimeRoundsUp() {
        // Rounding down sends someone back at the wrong time. Rounding up at
        // worst means they arrive to find they can already sign in.
        assertEquals("another 2 hours", WorkerAccess.humanise(Duration.ofMinutes(61)));
        assertEquals("another 1 hour", WorkerAccess.humanise(Duration.ofMinutes(60)));
        assertEquals("another 30 minutes", WorkerAccess.humanise(Duration.ofMinutes(30)));
        assertEquals("another 1 minute", WorkerAccess.humanise(Duration.ofSeconds(1)));
        assertEquals("another 1 day", WorkerAccess.humanise(Duration.ofHours(24)));
        assertEquals("another 2 days", WorkerAccess.humanise(Duration.ofHours(25)));
    }

    @Test
    @DisplayName("a phone number is matched however it was typed")
    void mobileIsNormalised() {
        // The shop wrote it down one way and the rider says it another.
        assertEquals("6388293365", WorkerAuthService.normaliseMobile("6388293365"));
        assertEquals("6388293365", WorkerAuthService.normaliseMobile("+91 63882 93365"));
        assertEquals("6388293365", WorkerAuthService.normaliseMobile("063882-93365"));
        assertEquals("", WorkerAuthService.normaliseMobile("not a number"));
    }
}
