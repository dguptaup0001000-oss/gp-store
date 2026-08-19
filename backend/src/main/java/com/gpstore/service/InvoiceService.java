package com.gpstore.service;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Invoice;
import com.gpstore.entity.Order;
import com.gpstore.entity.OrderItem;
import com.gpstore.enums.InvoiceStatus;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.InvoiceRepository;
import com.gpstore.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final OrderRepository orderRepository;
    private final TaxService taxService;

    public InvoiceService(
            InvoiceRepository invoiceRepository,
            OrderRepository orderRepository,
            TaxService taxService) {

        this.invoiceRepository = invoiceRepository;
        this.orderRepository = orderRepository;
        this.taxService = taxService;
    }

    /**
     * Every amount here is computed FROM THE ORDER, never accepted from a
     * request body - this was the same "self-reported financial data" bug
     * fixed on payments earlier, just not caught here yet. An invoice is a
     * legal/financial record; a client (or an admin's typo) should never be
     * able to set its numbers directly.
     *
     * Tax is computed from each order item's SNAPSHOTTED gstRate (captured at
     * purchase time in OrderService) - never recomputed from current
     * category/variant settings, so a past invoice can't silently change if
     * you update GST rates later. GST is treated as already included in the
     * selling price (the Indian retail norm), so this never changes what the
     * customer paid - it only reports how much of that amount was tax.
     */
    /**
     * Idempotent entry point, for callers that may legitimately run twice -
     * specifically the outbox worker, whose delivery is at-least-once.
     *
     * generateForOrder signals "already exists" by THROWING, which is right
     * for an admin clicking a button twice but wrong for a retry: a
     * ConflictException raised inside a @Transactional method marks the
     * surrounding transaction rollback-only, so a caller that catches it
     * still cannot commit - the work is undone underneath it and the commit
     * fails with UnexpectedRollbackException. Catching the exception is
     * therefore not enough; the exception must not be raised at all.
     *
     * @return the existing invoice if there is one, otherwise a freshly
     *         generated one.
     */
    @Transactional
    public com.gpstore.dto.response.InvoiceResponse generateForOrderIfAbsent(Long orderId) {
        return invoiceRepository.findByOrderId(orderId)
                .map(com.gpstore.dto.response.InvoiceResponse::from)
                .orElseGet(() -> generateForOrder(orderId));
    }

    @Transactional
    public com.gpstore.dto.response.InvoiceResponse generateForOrder(Long orderId) {

        if (invoiceRepository.findByOrderId(orderId).isPresent()) {
            throw new ConflictException("An invoice already exists for this order");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        Customer customer = order.getCustomer();

        BigDecimal deliveryFee = order.getDeliveryFee() != null ? order.getDeliveryFee() : BigDecimal.ZERO;
        BigDecimal discountAmount = order.getDiscountAmount() != null ? order.getDiscountAmount() : BigDecimal.ZERO;
        BigDecimal grandTotal = order.getTotalAmount();

        BigDecimal taxAmount = BigDecimal.ZERO;
        if (order.getOrderItems() != null) {
            for (OrderItem item : order.getOrderItems()) {
                taxAmount = taxAmount.add(taxService.extractTaxComponent(item.getTotalPrice(), item.getGstRate()));
            }
        }

        BigDecimal subtotal = grandTotal.subtract(deliveryFee).add(discountAmount).subtract(taxAmount);

        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber("INV-" + order.getOrderNumber());
        invoice.setOrder(order);
        invoice.setCustomer(customer);
        invoice.setInvoiceDate(LocalDateTime.now());
        invoice.setSubtotal(subtotal);
        invoice.setTaxAmount(taxAmount);
        invoice.setDiscountAmount(discountAmount);
        invoice.setDeliveryCharge(deliveryFee);
        invoice.setGrandTotal(grandTotal);
        invoice.setStatus(InvoiceStatus.GENERATED);
        invoice.setActive(true);

        return com.gpstore.dto.response.InvoiceResponse.from(invoiceRepository.save(invoice));
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<com.gpstore.dto.response.InvoiceResponse> getAllInvoices(
            org.springframework.data.domain.Pageable pageable) {
        return invoiceRepository.findAll(pageable)
                .map(com.gpstore.dto.response.InvoiceResponse::from);
    }

    @Transactional(readOnly = true)


    public Optional<com.gpstore.dto.response.InvoiceResponse> getInvoiceById(Long id) {


        return invoiceRepository.findById(id).map(com.gpstore.dto.response.InvoiceResponse::from);
    }

    @Transactional(readOnly = true)


    public Optional<com.gpstore.dto.response.InvoiceResponse> getInvoiceByOrderId(Long orderId) {


        return invoiceRepository.findByOrderId(orderId).map(com.gpstore.dto.response.InvoiceResponse::from);
    }

    /**
     * Customer-facing - their own invoice for one of THEIR OWN orders.
     * Every existing /api/invoices/** endpoint before this was admin-only,
     * meaning a customer had no way to view their own GST invoice at all -
     * a real gap for an Indian business where customers legitimately need
     * this for tax/expense records.
     */
    @Transactional(readOnly = true)
    public com.gpstore.dto.response.InvoiceResponse getOwnedInvoiceForOrder(Long orderId, Long customerId) {
        Invoice invoice = invoiceRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("No invoice found for this order"));

        if (invoice.getCustomer() == null || !invoice.getCustomer().getId().equals(customerId)) {
            // Same "hide with generic not-found" pattern used everywhere
            // else in this codebase for ownership violations.
            throw new ResourceNotFoundException("No invoice found for this order");
        }

        return com.gpstore.dto.response.InvoiceResponse.from(invoice);
    }

    @Transactional(readOnly = true)


    public Optional<com.gpstore.dto.response.InvoiceResponse> getInvoiceByInvoiceNumber(String invoiceNumber) {


        return invoiceRepository.findByInvoiceNumber(invoiceNumber).map(com.gpstore.dto.response.InvoiceResponse::from);
    }

    @Transactional


    public com.gpstore.dto.response.InvoiceResponse cancelInvoice(Long id) {
        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));

        invoice.setStatus(InvoiceStatus.CANCELLED);

        return com.gpstore.dto.response.InvoiceResponse.from(invoiceRepository.save(invoice));
    }
}
