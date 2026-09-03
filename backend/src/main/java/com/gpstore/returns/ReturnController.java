package com.gpstore.returns;

import com.gpstore.entity.OrderReturn;
import com.gpstore.repository.OrderReturnRepository;
import com.gpstore.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Returns, from both sides of the counter.
 *
 * WHO MAY DO WHAT is enforced in SecurityConfig, not by trusting a path.
 * Every customer route derives the account from the token and never from the
 * URL or body, so there is no id to tamper with; the staff routes sit behind
 * an explicit permission matcher. Both matter: /api/returns sits under
 * anyRequest().authenticated(), so a staff route without its own rule would
 * be open to any signed-in shopper.
 */
@RestController
@RequestMapping("/api/returns")
public class ReturnController {

    private final ReturnService returnService;
    private final OrderReturnRepository returnRepository;
    private final CurrentUser currentUser;

    public ReturnController(ReturnService returnService,
                            OrderReturnRepository returnRepository,
                            CurrentUser currentUser) {
        this.returnService = returnService;
        this.returnRepository = returnRepository;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------- customer

    /** What is still returnable on one of MY orders, so the app can draw the form. */
    @GetMapping("/orders/{orderId}/returnable")
    public Map<Long, Integer> returnable(@PathVariable Long orderId) {
        return returnService.returnableLines(currentUser.customerId(), orderId);
    }

    /** Ask to send items back. The account comes from the token. */
    @PostMapping("/orders/{orderId}")
    public ReturnResponse request(@PathVariable Long orderId,
                                  @Valid @RequestBody ReturnRequestBody body) {
        return ReturnResponse.from(
                returnService.request(currentUser.customerId(), orderId, body.getLines(), body.getReason()));
    }

    /** My returns, newest first. */
    @GetMapping("/me")
    public Page<ReturnResponse> mine(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return returnService.mine(currentUser.customerId(), pageable);
    }

    /** Change my mind, while nobody has decided yet. */
    @PostMapping("/{returnId}/cancel")
    public ReturnResponse cancel(@PathVariable Long returnId) {
        return ReturnResponse.from(returnService.cancel(returnId, currentUser.customerId()));
    }

    // ---------------------------------------------------------------- staff

    /** The queue: what is waiting for a decision, oldest first. */
    @GetMapping("/pending")
    public Page<ReturnResponse> pending(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return returnService.awaitingDecision(pageable);
    }

    /** Take the goods back: stock returns and a refund is opened. */
    @PostMapping("/{returnId}/approve")
    public ReturnResponse approve(@PathVariable Long returnId) {
        return ReturnResponse.from(returnService.approve(returnId, currentUser.customerId()));
    }

    /** Refuse it, with a reason the customer will read. */
    @PostMapping("/{returnId}/reject")
    public ReturnResponse reject(@PathVariable Long returnId,
                                 @Valid @RequestBody ReturnDecisionBody body) {
        return ReturnResponse.from(
                returnService.reject(returnId, currentUser.customerId(), body.getNote()));
    }

    /** How many are waiting, for the admin dashboard's badge. */
    @GetMapping("/pending/count")
    public Map<String, Long> pendingCount() {
        return Map.of("pending", returnRepository.countByStatus(OrderReturn.Status.REQUESTED));
    }
}
