package com.gpstore.governance;

import java.time.LocalDateTime;

/**
 * One governance action, as the merchant and the platform both see it.
 *
 * <p>THE SAME SHAPE FOR BOTH SIDES, on purpose. §2 asks for transparency, and
 * transparency is not two different renderings of the same event - if the
 * platform's view carried a field the merchant's did not, the merchant would
 * be answering a case they could only partly read. The only thing withheld
 * anywhere is the identity of the reviewer, which is a person's name and not
 * part of the case.
 */
public record GovernanceView(
        Long id,
        Long merchantId,
        Long aboutShopId,
        GovernanceLevel level,
        GovernanceReason reason,
        String detail,
        LocalDateTime issuedAt,
        LocalDateTime expiresAt,
        Long supersedesId,
        String appealText,
        LocalDateTime appealedAt,
        AppealOutcome appealOutcome,
        String appealNote,
        LocalDateTime appealDecidedAt,
        boolean appealOpen,
        boolean stopsTrading) {

    public static GovernanceView from(MerchantGovernanceAction action) {
        return new GovernanceView(
                action.getId(), action.getMerchantId(), action.getAboutShopId(),
                action.getLevel(), action.getReasonCode(), action.getDetail(),
                action.getIssuedAt(), action.getExpiresAt(), action.getSupersedesId(),
                action.getAppealText(), action.getAppealedAt(),
                action.getAppealOutcome(), action.getAppealNote(), action.getAppealDecidedAt(),
                action.isAppealOpen(), action.getLevel().stopsTrading());
    }
}
