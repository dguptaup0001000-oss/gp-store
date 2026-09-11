package com.gpstore.governance;

/**
 * The rungs of the ladder (§2), in order.
 *
 * <p>A LADDER, NOT A SWITCH. Before this existed the platform had exactly two
 * positions for a merchant who was causing problems - fine, or suspended -
 * which meant every intervention was either nothing at all or the end of
 * their business. A warning that is recorded, visible and appealable is the
 * rung that makes the top of the ladder legitimate.
 *
 * <p>ORDINAL ORDER IS LOAD-BEARING here, which is normally a smell. It is
 * declared deliberately: {@link #isAbove} is what stops a suspension being
 * issued to a merchant who has never been warned, and the whole point of §2
 * is that the sequence is not skippable.
 */
public enum GovernanceLevel {

    /** Recorded, visible to the merchant, and it expires. */
    WARNING(1),

    /** The last one before trading stops. */
    FINAL_WARNING(2),

    /** The shops stop trading. Reversible. */
    SUSPENSION(3),

    /** The end of the relationship. */
    TERMINATION(4),

    /**
     * Lifting something. Not a rung - it points DOWN the ladder.
     *
     * <p>Rank zero so it never satisfies {@link #isAbove}: a reinstatement
     * must never count as prior discipline the next escalation can stand on.
     */
    REINSTATEMENT(0);

    private final int rank;

    GovernanceLevel(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean isAbove(GovernanceLevel other) {
        return other == null || this.rank > other.rank;
    }

    /** Whether this one actually stops the merchant trading. */
    public boolean stopsTrading() {
        return this == SUSPENSION || this == TERMINATION;
    }

    /** The rung immediately below, or null at the bottom. */
    public GovernanceLevel below() {
        return switch (this) {
            case FINAL_WARNING -> WARNING;
            case SUSPENSION -> FINAL_WARNING;
            case TERMINATION -> SUSPENSION;
            case WARNING, REINSTATEMENT -> null;
        };
    }
}
