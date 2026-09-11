package com.gpstore.rating;

import com.gpstore.exception.BadRequestException;
import com.gpstore.platform.CustomerOwnedRead;
import com.gpstore.security.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rating the shop, from all three sides of it.
 *
 * <p>ONE CONTROLLER, THREE AUDIENCES, and the separation that matters is not
 * which class a method lives in - it is the authorization rule on the route,
 * in SecurityConfig, which a hand-built request cannot skip. Splitting these
 * across three classes would make the file listing tidier and the security
 * story no different.
 *
 * <p>THE SHOP IS NEVER IN THE URL. A storefront's ratings are the ratings of
 * the shop in scope; a merchant's are the ratings of the shop their
 * credential resolved to. Adding a {shopId} path variable would create
 * exactly the "a client may name its tenant" hole §78 rules out.
 */
@RestController
@RequestMapping("/api/shop-ratings")
public class ShopRatingController {

    private static final int MAX_PAGE = 50;

    private final ShopRatingService service;
    private final CurrentUser currentUser;
    private final CustomerOwnedRead customerOwnedRead;

    public ShopRatingController(ShopRatingService service, CurrentUser currentUser,
                                CustomerOwnedRead customerOwnedRead) {
        this.service = service;
        this.currentUser = currentUser;
        this.customerOwnedRead = customerOwnedRead;
    }

    // ------------------------------------------------------- the customer

    /**
     * Rate the shop for one order.
     *
     * <p>Read across shops because the order being rated need not be from
     * the shop the customer is currently browsing - one account, orders from
     * several kiranas (§5). The rating itself is written into the ORDER's
     * shop; see ShopRatingService.rate.
     */
    @PostMapping
    public ShopRatingView rate(@RequestBody Map<String, Object> request) {
        Long orderId = asLong(request.get("orderId"), "orderId");
        int stars = Math.toIntExact(asLong(request.get("rating"), "rating"));
        String comment = request.get("comment") == null ? null : String.valueOf(request.get("comment"));
        Set<ShopRatingReason> reasons = parseReasons(request.get("reasons"));
        Long me = currentUser.customerId();

        return customerOwnedRead.acrossShops(
                () -> ShopRatingView.from(service.rate(me, orderId, stars, reasons, comment)));
    }

    /** Everything this customer has rated, at any shop. */
    @GetMapping("/mine")
    public List<ShopRatingView> mine() {
        Long me = currentUser.customerId();
        return customerOwnedRead.acrossShops(() -> service.leftBy(me))
                .stream().map(ShopRatingView::from).toList();
    }

    /** The customer's single reply to the shop's single response (§21). */
    @PostMapping("/{ratingId}/reply")
    public ShopRatingView reply(@PathVariable Long ratingId,
                                @RequestBody Map<String, String> request) {
        Long me = currentUser.customerId();
        return customerOwnedRead.acrossShops(
                () -> ShopRatingView.from(service.customerReply(ratingId, me, request.get("text"))));
    }

    // ----------------------------------------------------- the storefront

    /** The three figures §19 asks for, for the shop in scope. */
    @GetMapping("/summary")
    public ShopRatingSummary summary() {
        return service.summary();
    }

    /** What other customers said about this shop. Hidden ones never appear. */
    @GetMapping
    public Page<ShopRatingView> visible(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return service.visible(paged(page, size)).map(ShopRatingView::from);
    }

    // ------------------------------------------------------- the merchant

    /** The merchant's own list, hidden ratings included and marked as such. */
    @GetMapping("/manage")
    public Page<ShopRatingView> manage(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.all(paged(page, size)).map(ShopRatingView::from);
    }

    /** The shop's one response (§21). */
    @PostMapping("/{ratingId}/respond")
    public ShopRatingView respond(@PathVariable Long ratingId,
                                  @RequestBody Map<String, String> request) {
        return ShopRatingView.from(
                service.merchantRespond(ratingId, request.get("text"), actor()));
    }

    /**
     * The shop flags one for a platform reviewer (§22).
     *
     * <p>It stays visible and stays in the average. See
     * ShopRatingService.report for why that is the whole point.
     */
    @PostMapping("/{ratingId}/report")
    public ShopRatingView report(@PathVariable Long ratingId,
                                 @RequestBody Map<String, String> request) {
        return ShopRatingView.from(service.report(ratingId, request.get("reason"), actor()));
    }

    // ------------------------------------------------- the platform reviewer

    /** What has been flagged, oldest problem first. */
    @GetMapping("/reported")
    public Page<ShopRatingView> reported(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.reported(paged(page, size)).map(ShopRatingView::from);
    }

    /**
     * Stop showing the words (§20).
     *
     * <p>The reason is required and must be one of {@link HideReason} - there
     * is no free-text field to write "unfair" into, deliberately.
     */
    @PostMapping("/{ratingId}/hide")
    public ShopRatingView hide(@PathVariable Long ratingId,
                               @RequestBody Map<String, String> request) {
        return ShopRatingView.from(
                service.hide(ratingId, parseHideReason(request.get("reason")), actor()));
    }

    @PostMapping("/{ratingId}/unhide")
    public ShopRatingView unhide(@PathVariable Long ratingId) {
        return ShopRatingView.from(service.unhide(ratingId, actor()));
    }

    /** The closed list, so a screen can draw it rather than invent one. */
    @GetMapping("/hide-reasons")
    public List<HideReason> hideReasons() {
        return List.of(HideReason.values());
    }

    /** The reason codes a customer may pick from (§18). */
    @GetMapping("/reasons")
    public List<Map<String, Object>> reasons() {
        return java.util.Arrays.stream(ShopRatingReason.values())
                .map(r -> Map.<String, Object>of("code", r.name(), "praise", r.isPraise()))
                .toList();
    }

    // ------------------------------------------------------------- helpers

    private static Pageable paged(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE));
    }

    private String actor() {
        return "admin:" + currentUser.customerId();
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

    private static Set<ShopRatingReason> parseReasons(Object raw) {
        Set<ShopRatingReason> parsed = new LinkedHashSet<>();
        if (raw == null) {
            return parsed;
        }
        if (!(raw instanceof Iterable<?> items)) {
            throw new BadRequestException("reasons must be a list of codes.");
        }
        for (Object item : items) {
            String code = String.valueOf(item).trim().toUpperCase(Locale.ROOT);
            try {
                parsed.add(ShopRatingReason.valueOf(code));
            } catch (IllegalArgumentException unknown) {
                // REJECTED, NOT IGNORED. Silently dropping an unrecognised
                // code would let a client version drift out of step with the
                // server and nobody find out until a shopkeeper wondered why
                // half their ratings had no reason on them.
                throw new BadRequestException("Not a rating reason: " + code);
            }
        }
        return parsed;
    }

    private static HideReason parseHideReason(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException(
                    "A reason is required, and it has to be one of: "
                            + java.util.Arrays.toString(HideReason.values()));
        }
        try {
            return HideReason.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException(
                    "'" + raw + "' is not a reason a rating may be hidden for. §20 keeps "
                            + "genuine negative reviews, so the list is closed: "
                            + java.util.Arrays.toString(HideReason.values()));
        }
    }
}
