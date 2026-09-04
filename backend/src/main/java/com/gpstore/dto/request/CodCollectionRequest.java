package com.gpstore.dto.request;

import java.math.BigDecimal;

/**
 * What the rider took at the door, split by how it arrived.
 *
 * Both fields together must equal the amount due. That is checked on the
 * server against the stored payment amount, never against anything the phone
 * sends - the rider says HOW the money arrived, never HOW MUCH is owed.
 */
public class CodCollectionRequest {

    private BigDecimal cashAmount;
    private BigDecimal upiAmount;

    public BigDecimal getCashAmount() { return cashAmount; }
    public void setCashAmount(BigDecimal cashAmount) { this.cashAmount = cashAmount; }

    public BigDecimal getUpiAmount() { return upiAmount; }
    public void setUpiAmount(BigDecimal upiAmount) { this.upiAmount = upiAmount; }
}
