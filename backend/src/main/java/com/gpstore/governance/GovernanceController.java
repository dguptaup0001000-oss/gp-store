package com.gpstore.governance;

import com.gpstore.exception.BadRequestException;
import com.gpstore.security.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The governance ladder, from both ends (§2).
 *
 * <p>TWO PREFIXES, TWO AUTHORIZATION RULES. Everything under
 * {@code /api/platform/governance} is the platform's - issuing, lifting,
 * deciding appeals - and SecurityConfig already gates the whole
 * {@code /api/platform/**} tree behind PLATFORM_ADMIN. Everything under
 * {@code /api/shop/governance} is the MERCHANT's own record, and takes no
 * merchant id at all: it is derived from the shop the credential resolved
 * to, because a merchant id a caller could name is a merchant id a caller
 * could change into somebody else's disciplinary file.
 */
@RestController
public class GovernanceController {

    private static final int MAX_PAGE = 100;

    private final MerchantGovernance governance;
    private final CurrentUser currentUser;

    public GovernanceController(MerchantGovernance governance, CurrentUser currentUser) {
        this.governance = governance;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------- the platform

    /** Issues a step. Refused if it skips a rung without a severe reason. */
    @PostMapping("/api/platform/governance/actions")
    public GovernanceView issue(@RequestBody Map<String, Object> request) {
        Long merchantId = asLong(request.get("merchantId"), "merchantId");
        Long shopId = request.get("shopId") == null ? null : asLong(request.get("shopId"), "shopId");
        GovernanceLevel level = parseLevel(request.get("level"));
        GovernanceReason reason = parseReason(request.get("reason"));
        String detail = request.get("detail") == null ? null : String.valueOf(request.get("detail"));
        return GovernanceView.from(
                governance.issue(merchantId, shopId, level, reason, detail, actor()));
    }

    /** Lifts one, recording the lifting as its own step. */
    @PostMapping("/api/platform/governance/actions/{actionId}/reinstate")
    public GovernanceView reinstate(@PathVariable Long actionId,
                                    @RequestBody(required = false) Map<String, String> request) {
        String note = request == null ? null : request.get("note");
        return GovernanceView.from(governance.reinstate(actionId, note, actor()));
    }

    /** Decides an appeal: UPHELD, REDUCED or OVERTURNED. */
    @PostMapping("/api/platform/governance/actions/{actionId}/appeal-decision")
    public GovernanceView decide(@PathVariable Long actionId,
                                 @RequestBody Map<String, String> request) {
        return GovernanceView.from(governance.decideAppeal(
                actionId, parseOutcome(request.get("outcome")), request.get("note"), actor()));
    }

    /** Appeals nobody has decided yet, oldest first - they are people waiting. */
    @GetMapping("/api/platform/governance/appeals")
    public Page<GovernanceView> openAppeals(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return governance.openAppeals(paged(page, size)).map(GovernanceView::from);
    }

    @GetMapping("/api/platform/governance/actions")
    public Page<GovernanceView> everything(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return governance.everything(paged(page, size)).map(GovernanceView::from);
    }

    /** One merchant's whole record and where they stand. */
    @GetMapping("/api/platform/governance/merchants/{merchantId}")
    public Map<String, Object> forMerchant(@PathVariable Long merchantId) {
        return Map.of(
                "standing", governance.standing(merchantId),
                "actions", governance.historyFor(merchantId).stream()
                        .map(GovernanceView::from).toList());
    }

    /** The closed lists, so a screen draws them rather than inventing them. */
    @GetMapping("/api/platform/governance/reasons")
    public List<Map<String, Object>> reasons() {
        return java.util.Arrays.stream(GovernanceReason.values())
                .map(r -> Map.<String, Object>of(
                        "code", r.name(),
                        "allowsImmediateSuspension", r.allowsImmediateSuspension()))
                .toList();
    }

    // ------------------------------------------------------- the merchant

    /**
     * The merchant's own record. No merchant id anywhere in the request.
     *
     * <p>This is what makes §2's "transparent" true rather than aspirational:
     * a shopkeeper can read every step taken against them, the reason, the
     * evidence, and what happened to their appeal, without asking anybody.
     */
    @GetMapping("/api/shop/governance")
    public Map<String, Object> mine() {
        return Map.of(
                "standing", governance.standingForShop(),
                "actions", governance.historyForShop().stream()
                        .map(GovernanceView::from).toList());
    }

    /** The merchant answers back, once per action. */
    @PostMapping("/api/shop/governance/actions/{actionId}/appeal")
    public GovernanceView appeal(@PathVariable Long actionId,
                                 @RequestBody Map<String, String> request) {
        Long merchantId = governance.merchantOfShopInScope();
        if (merchantId == null) {
            throw new BadRequestException("No shop in scope to appeal on behalf of.");
        }
        return GovernanceView.from(
                governance.appeal(actionId, merchantId, request.get("text")));
    }

    // ------------------------------------------------------------- helpers

    private static org.springframework.data.domain.Pageable paged(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE));
    }

    private String actor() {
        return "platform:" + currentUser.customerId();
    }

    private static Long asLong(Object raw, String field) {
        if (raw == null) {
            throw new BadRequestException(field + " is required.");
        }
        try {
            return Long.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException notANumber) {
            throw new BadRequestException(field + " must be a number.");
        }
    }

    private static GovernanceLevel parseLevel(Object raw) {
        if (raw == null) {
            throw new BadRequestException("level is required: "
                    + java.util.Arrays.toString(GovernanceLevel.values()));
        }
        try {
            return GovernanceLevel.valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException("'" + raw + "' is not a governance level: "
                    + java.util.Arrays.toString(GovernanceLevel.values()));
        }
    }

    private static GovernanceReason parseReason(Object raw) {
        if (raw == null) {
            throw new BadRequestException(
                    "A reason is required, and it has to be one of: "
                            + java.util.Arrays.toString(GovernanceReason.values()));
        }
        try {
            return GovernanceReason.valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException(
                    "'" + raw + "' is not a reason a merchant may be disciplined for. The list "
                            + "is closed so that every action can be compared and appealed: "
                            + java.util.Arrays.toString(GovernanceReason.values()));
        }
    }

    private static AppealOutcome parseOutcome(String raw) {
        if (raw == null) {
            throw new BadRequestException("outcome is required: UPHELD, REDUCED or OVERTURNED.");
        }
        try {
            return AppealOutcome.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException(
                    "'" + raw + "' is not an appeal outcome: UPHELD, REDUCED or OVERTURNED.");
        }
    }
}
