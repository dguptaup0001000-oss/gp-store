package com.gpstore.worker;

import com.gpstore.entity.DeliveryPartner;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * May this worker use the worker app right now, and if not, what do we tell
 * them?
 *
 * PURE ON PURPOSE. Every rule that decides whether a rider gets in lives in
 * one place, takes the record and the current time as arguments, and touches
 * nothing else - no repository, no clock of its own, no Spring. So the
 * awkward cases (a suspension that expired thirty seconds ago, a worker
 * deleted while their app is open, one the shop never gave a password) are
 * ordinary unit tests rather than something you can only find on a phone.
 *
 * IT IS ALSO CHECKED TWICE, and that is the point. Once at sign-in, and again
 * on every authenticated request through the filter - because a suspension
 * that only took effect at the next login would let a worker who was just
 * barred keep working until their token expired.
 */
public final class WorkerAccess {

    private WorkerAccess() {
    }

    public enum Verdict {
        ALLOWED,
        /** Removed from the roster. Their history is kept; their login is not. */
        DELETED,
        /** Taken off duty indefinitely - the roster's own active flag. */
        INACTIVE,
        /** Barred until a moment that will arrive on its own. */
        SUSPENDED,
        /** On the roster, but the shop has not given them a login yet. */
        NO_LOGIN
    }

    /**
     * The answer, and the sentence to show for it.
     *
     * The message is built here rather than at the call site so the login
     * screen and the request filter cannot drift into telling the same
     * worker two different stories about the same account.
     */
    public record Decision(Verdict verdict, String message) {
        public boolean allowed() {
            return verdict == Verdict.ALLOWED;
        }
    }

    private static final Decision ALLOWED = new Decision(Verdict.ALLOWED, null);

    public static Decision check(DeliveryPartner worker, LocalDateTime now) {
        if (worker == null || worker.getDeletedAt() != null) {
            // Deliberately the same sentence as an unknown address. Telling
            // someone "that worker was deleted" confirms the address exists
            // to anyone guessing at the login screen.
            return new Decision(Verdict.DELETED,
                    "That login is not recognised. Check the details with the shop.");
        }
        if (!Boolean.TRUE.equals(worker.getActive())) {
            return new Decision(Verdict.INACTIVE,
                    "This worker account is switched off. Ask the shop to turn it back on.");
        }
        if (worker.getLoginEmail() == null || worker.getLoginEmail().isBlank()
                || worker.getPasswordHash() == null || worker.getPasswordHash().isBlank()) {
            return new Decision(Verdict.NO_LOGIN,
                    "The shop has not set up a login for this worker yet.");
        }
        LocalDateTime until = worker.getSuspendedUntil();
        if (until != null && until.isAfter(now)) {
            return new Decision(Verdict.SUSPENDED, suspendedMessage(worker, now));
        }
        return ALLOWED;
    }

    /**
     * "Paused for another 3 hours" beats a timestamp, because the worker is
     * standing in the street holding a phone and wants to know whether to
     * wait or to go home. The reason the shop typed is appended when there
     * is one.
     */
    private static String suspendedMessage(DeliveryPartner worker, LocalDateTime now) {
        StringBuilder message = new StringBuilder("Your access is paused for ")
                .append(humanise(Duration.between(now, worker.getSuspendedUntil())))
                .append('.');
        String reason = worker.getSuspensionReason();
        if (reason != null && !reason.isBlank()) {
            message.append(' ').append(reason.trim());
        }
        return message.toString();
    }

    /**
     * Rounded UP, never down. "Another 1 hour" on a pause with 61 minutes to
     * run sends someone back at the wrong time; erring long is a worker who
     * arrives to find they can already sign in.
     */
    static String humanise(Duration remaining) {
        long minutes = remaining.toMinutes();
        if (remaining.minusMinutes(minutes).toSeconds() > 0) {
            minutes++;
        }
        if (minutes < 60) {
            return "another " + Math.max(minutes, 1) + plural(Math.max(minutes, 1), " minute");
        }
        long hours = (minutes + 59) / 60;
        if (hours < 24) {
            return "another " + hours + plural(hours, " hour");
        }
        long days = (hours + 23) / 24;
        return "another " + days + plural(days, " day");
    }

    private static String plural(long value, String unit) {
        return value == 1 ? unit : unit + "s";
    }
}
