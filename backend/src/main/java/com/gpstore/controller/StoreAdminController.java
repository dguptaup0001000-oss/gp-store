package com.gpstore.controller;

import com.gpstore.entity.Order;
import com.gpstore.entity.StoreClosure;
import com.gpstore.entity.StoreOperationsSettings;
import com.gpstore.exception.BadRequestException;
import com.gpstore.repository.OrderRepository;
import com.gpstore.security.CurrentUser;
import com.gpstore.store.DeliveryScheduleService;
import com.gpstore.store.DeliveryWindow;
import com.gpstore.store.StoreOperationsService;
import com.gpstore.store.hours.ShopBusinessHours;
import com.gpstore.store.hours.ShopHoursOverride;
import com.gpstore.store.StoreOrderAcceptance;
import com.gpstore.store.StoreStatusResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shop's operating controls: the order switch, the closed days, and the
 * morning packing list.
 *
 * <p>AUTHORIZATION IS ON THE ROUTE, in SecurityConfig, not in this class and
 * certainly not in Flutter. Hiding a menu item stops nobody with curl. Every
 * path under here requires DELIVERY_MANAGE except the preparation list, which
 * only needs ORDERS_VIEW - the people who pack the boxes need to read it
 * without being able to shut the shop.
 */
@RestController
@RequestMapping("/api/admin/store")
public class StoreAdminController {

    /** Capped so a mistyped page size cannot ask for the whole order table. */
    private static final int MAX_PAGE_SIZE = 100;

    private final StoreOperationsService operationsService;
    private final DeliveryScheduleService scheduleService;
    private final OrderRepository orderRepository;
    private final CurrentUser currentUser;

    public StoreAdminController(
            StoreOperationsService operationsService,
            DeliveryScheduleService scheduleService,
            OrderRepository orderRepository,
            CurrentUser currentUser) {
        this.operationsService = operationsService;
        this.scheduleService = scheduleService;
        this.orderRepository = orderRepository;
        this.currentUser = currentUser;
    }

    /** The whole operations card in one response: switch, status, closures. */
    @GetMapping("/operations")
    public Map<String, Object> operations() {
        StoreOperationsSettings settings = operationsService.settings();
        Map<String, Object> body = new HashMap<>();
        body.put("orderAcceptance", settings.acceptanceOrDefault());
        body.put("closureMessage", settings.getClosureMessage());
        body.put("pausedUntil", settings.getPausedUntil());
        body.put("updatedAt", settings.getUpdatedAt());
        body.put("updatedBy", settings.getUpdatedBy());
        body.put("status", StoreStatusResponse.from(
                scheduleService.getStoreStatus(), scheduleService.getProperties(),
                scheduleService.shopZone()));
        body.put("closures", operationsService.upcomingClosures().stream()
                .map(StoreAdminController::closureJson)
                .toList());
        return body;
    }

    /**
     * Sets AUTO, ON or OFF.
     *
     * <p>An unrecognised value is rejected rather than defaulted. Quietly
     * reading a typo as AUTO would leave an owner who meant to pause orders
     * believing they had.
     */
    @PutMapping("/operations")
    public Map<String, Object> setAcceptance(@RequestBody Map<String, String> request) {
        String raw = request.get("orderAcceptance");
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("orderAcceptance is required: AUTO, ON or OFF.");
        }
        StoreOrderAcceptance acceptance;
        try {
            acceptance = StoreOrderAcceptance.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(
                    "orderAcceptance must be AUTO, ON or OFF - received '" + raw + "'.");
        }
        operationsService.setOrderAcceptance(acceptance, request.get("closureMessage"), actor());
        return operations();
    }

    @GetMapping("/closures")
    public List<Map<String, Object>> closures() {
        return operationsService.upcomingClosures().stream()
                .map(StoreAdminController::closureJson)
                .toList();
    }

    @PostMapping("/closures")
    public Map<String, Object> addClosure(@RequestBody Map<String, String> request) {
        LocalDate date = parseDate(request.get("date"));
        return closureJson(operationsService.addClosure(date, request.get("reason"), actor()));
    }

    /**
     * Reopens a day.
     *
     * <p>Addressed by DATE rather than by row id, deliberately: the admin
     * screen is a calendar, the thing being undone is "closed on the 14th",
     * and a date cannot select the wrong row the way a stale id can.
     */
    @DeleteMapping("/closures/{date}")
    public ResponseEntity<Void> removeClosure(@PathVariable String date) {
        operationsService.removeClosure(parseDate(date), actor());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // Stepping out.
    // ------------------------------------------------------------------

    /**
     * "Back in 30 minutes", "back at four", or "no more orders today".
     *
     * <p>ONE ROUTE, because they are one act with three different times, and
     * three routes would be three chances for them to disagree about what a
     * pause does to the orders already on the board. It does nothing to them:
     * see StoreOperationsService.pauseUntil and §12.
     *
     * <p>The body carries exactly one of minutes / until / restOfDay.
     */
    @PostMapping("/pause")
    public Map<String, Object> pause(@RequestBody Map<String, Object> request) {
        Object minutes = request.get("minutes");
        Object until = request.get("until");
        boolean restOfDay = Boolean.TRUE.equals(request.get("restOfDay"))
                || "true".equalsIgnoreCase(String.valueOf(request.get("restOfDay")));
        String reason = request.get("reason") == null ? null : String.valueOf(request.get("reason"));

        int given = (minutes != null ? 1 : 0) + (until != null ? 1 : 0) + (restOfDay ? 1 : 0);
        if (given != 1) {
            throw new BadRequestException(
                    "Say how long to pause for, exactly one way: minutes, until, or restOfDay.");
        }

        if (minutes != null) {
            operationsService.pauseForMinutes(parseMinutes(minutes), reason, actor());
        } else if (until != null) {
            operationsService.pauseUntil(parseLocalDateTime(String.valueOf(until)), reason, actor());
        } else {
            operationsService.pauseForRestOfDay(reason, actor());
        }
        return operations();
    }

    /** Back open now, whatever the pause said. */
    @PostMapping("/resume")
    public Map<String, Object> resume() {
        operationsService.resume(actor());
        return operations();
    }

    // ------------------------------------------------------------------
    // The shop's own week.
    // ------------------------------------------------------------------

    /**
     * This shop's trading hours.
     *
     * <p>AN EMPTY WEEK IS A REAL ANSWER and the app has to be able to draw it:
     * it means this shop has not set its own hours and trades on the
     * deployment's, which is where every shop starts and where Shop #1 has
     * been since long before shops had hours of their own.
     */
    @GetMapping("/hours")
    public Map<String, Object> hours() {
        Map<String, List<Map<String, String>>> week = new LinkedHashMap<>();
        for (ShopBusinessHours row : operationsService.weeklyHours()) {
            week.computeIfAbsent(row.day().name(), day -> new ArrayList<>())
                    .add(Map.of("opensAt", row.getOpensAt().toString(),
                            "closesAt", row.getClosesAt().toString()));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("week", week);
        body.put("usesOwnHours", !week.isEmpty());
        body.put("overrides", operationsService.upcomingHourOverrides().stream()
                .map(StoreAdminController::overrideJson).toList());
        return body;
    }

    /**
     * Replaces the whole week.
     *
     * <p>THE WHOLE WEEK, NEVER A DAY - a weekday with no sessions is closed,
     * so a per-day save cannot tell "we are still editing" from "we do not
     * open on Tuesdays". Body shape:
     * {@code {"week": {"MONDAY": [{"opensAt":"07:00","closesAt":"13:00"}, ...]}}}
     */
    @PutMapping("/hours")
    public Map<String, Object> setHours(@RequestBody Map<String, Object> request) {
        operationsService.replaceWeek(parseWeek(request.get("week")), actor());
        return hours();
    }

    /** One date's hours instead of that weekday's. An empty list restores the usual week. */
    @PutMapping("/hours/{date}")
    public Map<String, Object> setHoursOn(@PathVariable String date,
                                          @RequestBody Map<String, Object> request) {
        operationsService.setHoursOn(parseDate(date), parseSessions(request.get("sessions")),
                request.get("reason") == null ? null : String.valueOf(request.get("reason")),
                actor());
        return hours();
    }

    private static Map<String, Object> overrideJson(ShopHoursOverride row) {
        Map<String, Object> json = new HashMap<>();
        json.put("id", row.getId());
        json.put("date", row.getOnDate());
        json.put("opensAt", row.getOpensAt().toString());
        json.put("closesAt", row.getClosesAt().toString());
        json.put("reason", row.getReason());
        return json;
    }

    private static Map<java.time.DayOfWeek, List<StoreOperationsService.TradingSession>> parseWeek(
            Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> byDay)) {
            throw new BadRequestException("week must be an object keyed by weekday name.");
        }
        Map<java.time.DayOfWeek, List<StoreOperationsService.TradingSession>> week =
                new java.util.EnumMap<>(java.time.DayOfWeek.class);
        for (Map.Entry<?, ?> entry : byDay.entrySet()) {
            java.time.DayOfWeek day;
            try {
                day = java.time.DayOfWeek.valueOf(
                        String.valueOf(entry.getKey()).trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw new BadRequestException("Unknown weekday: " + entry.getKey());
            }
            List<StoreOperationsService.TradingSession> sessions = parseSessions(entry.getValue());
            // An empty list and an absent key both mean closed; keeping the
            // key would only make the audit line say "7 days" for a shop that
            // opens on two.
            if (!sessions.isEmpty()) {
                week.put(day, sessions);
            }
        }
        return week;
    }

    private static List<StoreOperationsService.TradingSession> parseSessions(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new BadRequestException("sessions must be a list of {opensAt, closesAt}.");
        }
        List<StoreOperationsService.TradingSession> sessions = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> session)) {
                throw new BadRequestException("Each session must be {opensAt, closesAt}.");
            }
            sessions.add(new StoreOperationsService.TradingSession(
                    parseTime(session.get("opensAt")), parseTime(session.get("closesAt"))));
        }
        return sessions;
    }

    private static java.time.LocalTime parseTime(Object raw) {
        if (raw == null) {
            throw new BadRequestException("A session needs opensAt and closesAt, as HH:mm.");
        }
        try {
            return java.time.LocalTime.parse(String.valueOf(raw).trim());
        } catch (java.time.format.DateTimeParseException badTime) {
            throw new BadRequestException("'" + raw + "' is not a time in HH:mm form.");
        }
    }

    private static java.time.LocalDateTime parseLocalDateTime(String raw) {
        try {
            return java.time.LocalDateTime.parse(raw.trim());
        } catch (java.time.format.DateTimeParseException badTime) {
            throw new BadRequestException(
                    "'" + raw + "' is not a local date and time (YYYY-MM-DDTHH:mm).");
        }
    }

    private static int parseMinutes(Object raw) {
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException notANumber) {
            throw new BadRequestException("minutes must be a whole number.");
        }
    }

    /**
     * What has to be packed for a given day's 09:00 run.
     *
     * <p>PAGED, AND NARROWED IN THE DATABASE. Defaults to the next delivery
     * day, which overnight is today's 09:00 run - the list whoever arrives at
     * 08:00 actually wants.
     */
    @GetMapping("/preparation")
    @Transactional(readOnly = true)
    public Map<String, Object> preparation(
            @RequestParam(required = false) String date,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        LocalDate target = date == null || date.isBlank() ? defaultPreparationDate() : parseDate(date);
        if (target == null) {
            // No reachable delivery day - the shop is closed past the
            // lookahead. An empty list with the reason beats a 500.
            Map<String, Object> body = new HashMap<>();
            body.put("date", null);
            body.put("totalOrders", 0L);
            body.put("orders", List.of());
            body.put("message", "No delivery day is scheduled - the shop is marked closed.");
            return body;
        }

        Page<Order> orders = orderRepository.findForPreparation(
                target, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE)));

        List<Map<String, Object>> rows = new ArrayList<>(orders.getNumberOfElements());
        for (Order order : orders.getContent()) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", order.getId());
            row.put("orderNumber", order.getOrderNumber());
            row.put("orderStatus", order.getOrderStatus());
            row.put("paymentStatus", order.getPaymentStatus());
            row.put("deliveryType", order.getDeliveryType());
            row.put("orderDate", order.getOrderDate());
            row.put("totalAmount", order.getTotalAmount());
            rows.add(row);
        }

        // NULL WHEN THE SHOP DOES NOT TRADE THAT DAY, which became possible the
        // moment shops got their own weeks: asking for Sunday's packing list at
        // a shop that shuts on Sundays is a fair question with the answer "no
        // run". The order list is still returned - orders scheduled for a day
        // the shop has since stopped trading are exactly the ones somebody
        // needs to see - so only the two times are omitted.
        DeliveryWindow window = scheduleService.windowOn(target);
        Map<String, Object> body = new HashMap<>();
        body.put("date", target);
        body.put("packingStartsAt", window == null ? null : window.preparation());
        body.put("deliveriesStartAt", window == null ? null : window.start());
        body.put("totalOrders", orders.getTotalElements());
        body.put("page", orders.getNumber());
        body.put("size", orders.getSize());
        body.put("totalPages", orders.getTotalPages());
        body.put("orders", rows);
        return body;
    }

    private LocalDate defaultPreparationDate() {
        DeliveryWindow next = scheduleService.getNextDeliveryWindow();
        return next == null ? null : next.date();
    }

    private static Map<String, Object> closureJson(StoreClosure closure) {
        Map<String, Object> json = new HashMap<>();
        json.put("id", closure.getId());
        json.put("date", closure.getClosedOn());
        json.put("reason", closure.getReason());
        json.put("createdAt", closure.getCreatedAt());
        json.put("createdBy", closure.getCreatedBy());
        return json;
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("A date in YYYY-MM-DD form is required.");
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (java.time.format.DateTimeParseException ex) {
            throw new BadRequestException("'" + raw + "' is not a date in YYYY-MM-DD form.");
        }
    }

    /** Who to record in the audit log. Read from the JWT, never the request. */
    private String actor() {
        return "admin:" + currentUser.customerId();
    }
}
