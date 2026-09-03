-- How long a customer actually spends in the app.
--
-- WHY THIS TABLE HAS TO EXIST AT ALL. Presence already answers "who is using
-- the shop right now" - a rolling five-minute window in Redis holding
-- userId -> last-seen. That is deliberately ephemeral telemetry for a
-- dashboard, and it cannot answer "how long has this person spent with us",
-- because it never remembers yesterday.
--
-- WHAT IS RECORDED, AND WHAT IS DELIBERATELY NOT. A start, an end, and a
-- duration. NOT which screens they opened, not what they searched for, not
-- what they looked at and did not buy. That restraint is the whole design:
-- "this customer has spent 40 minutes in the app" is a usage figure a
-- shopkeeper can act on, while a screen-by-screen trail is a surveillance log
-- about somebody who came to buy atta. The narrower thing answers the
-- question that was asked and cannot be quietly repurposed into the wider one.
--
-- THE DURATION COMES FROM THE CLIENT, so it is not evidence. A phone can be
-- wrong about its own clock and a modified app can claim anything. The service
-- caps each session server-side; this column stores what survived that cap.
-- It is good enough to say "a regular" or "signed up and never came back",
-- which is what it is for, and it is not good enough to bill anyone.
--
-- THIS IS COLLECTED PERSONAL DATA. docs/PLAY_STORE_DECLARATIONS.md is updated
-- in the same change, because collecting app-interaction data without
-- declaring it is a false Play Store declaration.

CREATE TABLE IF NOT EXISTS customer_app_sessions (
    id           BIGSERIAL PRIMARY KEY,
    customer_id  BIGINT NOT NULL REFERENCES customers (id),

    -- Both ends kept rather than a duration alone, so "when do people shop"
    -- stays answerable later without another migration and another round of
    -- asking customers for data we already had.
    started_at   TIMESTAMP NOT NULL,
    ended_at     TIMESTAMP NOT NULL,

    -- Denormalised so the totals query is a SUM rather than a per-row
    -- subtraction across the whole table. Written by the server after the cap,
    -- never taken from the request as-is.
    seconds      INTEGER NOT NULL CHECK (seconds >= 0),

    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

-- The only query this table has: everything for one customer, newest first.
-- Composite so the customer detail screen's total and its recent-sessions
-- list are both index-only.
CREATE INDEX IF NOT EXISTS ix_customer_app_sessions_customer
    ON customer_app_sessions (customer_id, started_at DESC);
