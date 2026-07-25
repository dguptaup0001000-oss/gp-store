package com.gpstore.controller;

import com.gpstore.entity.Invoice;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.InvoiceService;
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
    public Invoice generateInvoice(@RequestParam Long orderId) {
        return invoiceService.generateForOrder(orderId);
    }

    @GetMapping
    public List<Invoice> getAllInvoices() {
        return invoiceService.getAllInvoices();
    }

    @GetMapping("/{id}")
    public Optional<Invoice> getInvoiceById(@PathVariable Long id) {
        return invoiceService.getInvoiceById(id);
    }

    @GetMapping("/order/{orderId}")
    public Optional<Invoice> getInvoiceByOrderId(@PathVariable Long orderId) {
        return invoiceService.getInvoiceByOrderId(orderId);
    }

    @GetMapping("/number/{invoiceNumber}")
    public Optional<Invoice> getInvoiceByInvoiceNumber(@PathVariable String invoiceNumber) {
        return invoiceService.getInvoiceByInvoiceNumber(invoiceNumber);
    }

    @PutMapping("/{id}/cancel")
    public Invoice cancelInvoice(@PathVariable Long id) {
        return invoiceService.cancelInvoice(id);
    }
}
