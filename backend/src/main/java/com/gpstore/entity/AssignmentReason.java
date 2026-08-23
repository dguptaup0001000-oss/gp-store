package com.gpstore.entity;

/**
 * Why THIS rider got THIS order - the audit trail the dynamic half of the
 * territory system is useless without.
 *
 * Without it, "why did a Z2 rider deliver in Z7 last Tuesday" is
 * unanswerable, and an overflow that should have been a one-off busy evening
 * becomes an invisible habit that quietly builds a case for redrawing a
 * boundary that was never the problem. Every assignment records which rung of
 * the ladder it came off.
 */
public enum AssignmentReason {

    /** The subzone's own rider, within capacity. The normal case. */
    PRIMARY,

    /** Primary is present but at capacity; a named or neighbouring rider took the overflow. */
    OVERFLOW,

    /** Primary is offline or unavailable; a standing backup covered the territory. */
    ABSENCE,

    /** Borrowed from elsewhere in the same main zone. */
    ZONE_SUPPORT,

    /** Borrowed from a neighbouring main zone - the last territory-aware rung. */
    NEIGHBOUR_ZONE_SUPPORT,

    /**
     * No territory information at all: the address resolved to no subzone, or
     * no rung of the ladder produced a geographically suitable candidate.
     *
     * The order is already placed and usually already paid, so this falls back
     * to the pre-territory least-loaded pick rather than leaving it
     * unassigned - but it is recorded distinctly, because a rising count here
     * means the map has a hole in it and no amount of dispatch tuning will fix
     * that.
     */
    FALLBACK,

    /** Assigned before this system existed. Never written by new code. */
    LEGACY
}
