package com.gpstore.governance;

/**
 * What a platform reviewer decided about a merchant's appeal (§2).
 *
 * <p>THREE, NOT TWO. An appeal process whose only answers are "no" and "yes"
 * pushes every borderline case to one of the extremes; REDUCED is what lets a
 * reviewer say "the conduct happened, the suspension was heavier than it
 * needed to be" without either pretending it did not happen or leaving the
 * shop shut.
 */
public enum AppealOutcome {

    /** The action stands as issued. */
    UPHELD,

    /** The conduct was real; the level comes down a rung. */
    REDUCED,

    /** The action should not have been taken. It is lifted. */
    OVERTURNED
}
