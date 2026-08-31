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
        body.put("updatedAt", settings.getUpdatedAt());
        body.put("updatedBy", settings.getUpdatedBy());
        body.put("status", StoreStatusResponse.from(
                scheduleService.getStoreStatus(), scheduleService.getProperties()));
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

        DeliveryWindow window = scheduleService.windowOn(target);
        Map<String, Object> body = new HashMap<>();
        body.put("date", target);
        body.put("packingStartsAt", window.preparation());
        body.put("deliveriesStartAt", window.start());
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
