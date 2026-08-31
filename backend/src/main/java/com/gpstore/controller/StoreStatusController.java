package com.gpstore.controller;

import com.gpstore.store.DeliveryScheduleService;
import com.gpstore.store.StoreStatusResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * "Is the shop taking orders, and when will this arrive?"
 *
 * <p>PUBLIC, and that is the point. A customer browsing at 3am before signing
 * in still needs to be told the shop is open and their order would arrive at
 * 9am. Gating this behind authentication would put a login wall in front of
 * the one message the whole feature exists to deliver.
 *
 * <p>NOTHING HERE IS AN AUTHORIZATION DECISION. It exposes the shop's own
 * opening hours and whether it is currently taking orders - the same facts a
 * sign on the door would carry - and no customer, order or staff data.
 */
@RestController
@RequestMapping("/api/store")
public class StoreStatusController {

    private final DeliveryScheduleService scheduleService;

    public StoreStatusController(DeliveryScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /**
     * The shop's current state.
     *
     * <p>CACHED FOR TEN SECONDS, and no longer. This is polled by every open
     * app, so an uncached response makes the home screen a query per customer
     * per refresh; but the countdown ticks every second near close, and a
     * minute-long cache would tell someone at 20:59 that they still have two
     * minutes. Ten seconds is short enough that the countdown is never wrong
     * by more than a rounding, and long enough to collapse a crowd.
     *
     * <p>PRIVATE, not public: the response embeds the server's clock, and a
     * shared proxy cache serving a stale timestamp to a later customer would
     * reintroduce exactly the clock-skew problem this endpoint solves.
     */
    @GetMapping("/status")
    public ResponseEntity<StoreStatusResponse> status() {
        StoreStatusResponse body = StoreStatusResponse.from(
                scheduleService.getStoreStatus(), scheduleService.getProperties());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(10, TimeUnit.SECONDS).cachePrivate())
                .body(body);
    }
}
