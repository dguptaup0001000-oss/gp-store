package com.gpstore.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * How long the app was open, as reported by the app.
 *
 * ONE FIELD, ON PURPOSE. There is no screen name here, no search term and no
 * product id - so no future change can quietly start collecting a browsing
 * trail by filling in a field that was already on the wire. A shopkeeper asked
 * "how much time do they spend in the app", and a duration answers that
 * without building a record of what somebody looked at.
 *
 * NO START TIME EITHER. The server stamps the clock. A phone's clock can be
 * wrong or set deliberately, and a session filed in 1970 would poison every
 * question this data is meant to answer later.
 *
 * The bounds here are a first sieve for obvious nonsense; the real cap lives
 * in AppSessionService, because a check that only exists in a DTO is a check
 * the next caller skips.
 */
public class AppSessionRequest {

    @NotNull(message = "Session length is required")
    @Min(value = 0, message = "A session cannot be negative")
    // A day. Anything beyond it is a broken clock, not a shopping trip.
    @Max(value = 86_400, message = "A session cannot be longer than a day")
    private Integer seconds;

    public Integer getSeconds() { return seconds; }
    public void setSeconds(Integer seconds) { this.seconds = seconds; }
}
