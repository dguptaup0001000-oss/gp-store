package com.gpstore.returns;

import jakarta.validation.constraints.*;

import java.util.Map;

/**
 * What a customer sends to ask for a return.
 *
 * NOTABLY ABSENT: an amount. The money is worked out server-side from the
 * order's own stored line prices, because the amount is the shop's money and
 * this object arrives from a phone. There is no field to put it in, which is
 * the strongest form of that rule.
 */
public class ReturnRequestBody {

    /** Order line id to how many units of it are coming back. */
    @NotEmpty(message = "Choose at least one item to return.")
    @Size(max = 50, message = "That is more lines than one return can carry.")
    private Map<@NotNull Long, @NotNull @Min(1) @Max(999) Integer> lines;

    @Size(max = 500)
    private String reason;

    public Map<Long, Integer> getLines() { return lines; }
    public void setLines(Map<Long, Integer> lines) { this.lines = lines; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
