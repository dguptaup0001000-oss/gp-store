package com.gpstore.governance;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Where a merchant currently stands on the ladder (§2).
 *
 * <p>COMPUTED, NEVER STORED. A "current level" column would be a second place
 * the truth lives, and the day it disagreed with the actions behind it,
 * somebody's shop would be shut for a reason nobody could point at. The
 * actions are the record; this is a reading of them.
 *
 * @param level     the highest rung still in force, or null for a clean record
 * @param liveCount how many actions are still counting
 * @param mayTrade  whether anything in force stops them trading
 * @param openAppeals actions the merchant has appealed and nobody has decided
 */
public record MerchantStanding(
        Long merchantId,
        GovernanceLevel level,
        int liveCount,
        boolean mayTrade,
        List<Long> openAppeals,
        LocalDateTime asOf) {

    public static MerchantStanding clean(Long merchantId, LocalDateTime asOf) {
        return new MerchantStanding(merchantId, null, 0, true, List.of(), asOf);
    }

    /** Nothing in force. Not the same as "never did anything wrong". */
    public boolean isClean() {
        return level == null;
    }

    /**
     * Whether the next rung up may be issued without a severe reason.
     *
     * <p>The ladder rule in one place: {@link MerchantGovernance} asks this
     * rather than re-deriving it, so the rule cannot drift between the check
     * and the screen that predicts the check.
     */
    public boolean supports(GovernanceLevel next) {
        if (next == null || next == GovernanceLevel.WARNING
                || next == GovernanceLevel.REINSTATEMENT) {
            return true;
        }
        GovernanceLevel needed = next.below();
        return level != null && needed != null && level.rank() >= needed.rank();
    }
}
