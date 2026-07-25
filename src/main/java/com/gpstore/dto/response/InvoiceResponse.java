package com.gpstore.dto.response;

import com.gpstore.entity.Invoice;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The full Customer/Order objects nested in the raw Invoice entity would be
 * redundant here - the customer viewing their own invoice already knows
 * who they are, and the order's own detail screen already shows the items.
 * This is just the invoice-specific financial record: what GST was
 * charged, on what subtotal, alongside the order number for reference.
 */
public class InvoiceResponse {

    private final Long invoiceId;
    private final String invoiceNumber;
    private final String orderNumber;
    private final LocalDateTime invoiceDate;
    private final BigDecimal subtotal;
    private final BigDecimal taxAmount;
    private final BigDecimal discountAmount;
    private final BigDecimal deliveryCharge;
    private final BigDecimal grandTotal;
    private final String status;

    public InvoiceResponse(Long invoiceId, String invoiceNumber, String orderNumber, LocalDateTime invoiceDate,
                            BigDecimal subtotal, BigDecimal taxAmount, BigDecimal discountAmount,
                            BigDecimal deliveryCharge, BigDecimal grandTotal, String status) {
        this.invoiceId = invoiceId;
        this.invoiceNumber = invoiceNumber;
        this.orderNumber = orderNumber;
        this.invoiceDate = invoiceDate;
        this.subtotal = subtotal;
        this.taxAmount = taxAmount;
        this.discountAmount = discountAmount;
        this.deliveryCharge = deliveryCharge;
        this.grandTotal = grandTotal;
        this.status = status;
    }

    public static InvoiceResponse from(Invoice invoice) {
        return new InvoiceResponse(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getOrder() != null ? invoice.getOrder().getOrderNumber() : null,
                invoice.getInvoiceDate(),
                invoice.getSubtotal(),
                invoice.getTaxAmount(),
                invoice.getDiscountAmount(),
                invoice.getDeliveryCharge(),
                invoice.getGrandTotal(),
                invoice.getStatus() != null ? invoice.getStatus().name() : null
        );
    }

    public Long getInvoiceId() { return invoiceId; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public String getOrderNumber() { return orderNumber; }
    public LocalDateTime getInvoiceDate() { return invoiceDate; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public BigDecimal getDeliveryCharge() { return deliveryCharge; }
    public BigDecimal getGrandTotal() { return grandTotal; }
    public String getStatus() { return status; }
}
