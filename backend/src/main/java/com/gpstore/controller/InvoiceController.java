package com.gpstore.controller;

import com.gpstore.entity.Invoice;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.InvoiceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final CurrentUser currentUser;

    public InvoiceController(InvoiceService invoiceService, CurrentUser currentUser) {
        this.invoiceService = invoiceService;
        this.currentUser = currentUser;
    }

    // Customer-facing - their own invoice for one of their own orders.
    // Ownership is verified server-side, never trusted from the order id
    // alone. This is the only non-admin endpoint on this controller.
    @GetMapping("/my-order/{orderId}")
    public com.gpstore.dto.response.InvoiceResponse getMyInvoiceForOrder(@PathVariable Long orderId) {
        return invoiceService.getOwnedInvoiceForOrder(orderId, currentUser.customerId());
    }

    // No request body - every amount is computed from the order itself, never
    // accepted from the client (see InvoiceService for why).
    @PostMapping
    public com.gpstore.dto.response.InvoiceResponse generateInvoice(@RequestParam Long orderId) {
        return invoiceService.generateForOrder(orderId);
    }

    @GetMapping
    public Page<com.gpstore.dto.response.InvoiceResponse> getAllInvoices(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return invoiceService.getAllInvoices(pageable);
    }

    @GetMapping("/{id}")
    public Optional<com.gpstore.dto.response.InvoiceResponse> getInvoiceById(@PathVariable Long id) {
        return invoiceService.getInvoiceById(id);
    }

    @GetMapping("/order/{orderId}")
    public Optional<com.gpstore.dto.response.InvoiceResponse> getInvoiceByOrderId(@PathVariable Long orderId) {
        return invoiceService.getInvoiceByOrderId(orderId);
    }

    @GetMapping("/number/{invoiceNumber}")
    public Optional<com.gpstore.dto.response.InvoiceResponse> getInvoiceByInvoiceNumber(@PathVariable String invoiceNumber) {
        return invoiceService.getInvoiceByInvoiceNumber(invoiceNumber);
    }

    @PutMapping("/{id}/cancel")
    public com.gpstore.dto.response.InvoiceResponse cancelInvoice(@PathVariable Long id) {
        return invoiceService.cancelInvoice(id);
    }
}
