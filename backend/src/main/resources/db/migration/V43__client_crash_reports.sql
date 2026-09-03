-- A crash on a rider's phone becomes something a person can read.
--
-- WHAT HAPPENS TODAY. The customer and admin APKs report crashes to
-- Crashlytics. The worker APK does not, and that is not an oversight - it
-- ships without Firebase on purpose so the rider's install stays small, so
-- worker_main.dart installs the crash handlers and hands them a
-- NoOpCrashReporter. The handlers run, the error is caught, and it is
-- dropped on the floor.
--
-- The consequence is the whole reason for this table. The worker app is the
-- one that runs all day, on the cheapest phone in the shop, while somebody is
-- standing in a street holding a carton. When it dies there, nobody finds
-- out: the rider restarts it and carries on, the shopkeeper hears "the app
-- closed", and there is no record anywhere of what happened. Every other part
-- of this system - orders, payments, refunds, stock - can be asked what went
-- wrong afterwards. The app the shop physically depends on cannot.
--
-- WHY NOT JUST ADD FIREBASE TO THE WORKER APK. Because the small APK is a
-- real decision, not an accident, and because the shop already runs a backend
-- the worker app is authenticated against for its whole shift. Posting the
-- crash to that backend costs no APK size, no new vendor, and no money.
--
-- WHAT THIS IS NOT. It is not Crashlytics. There is no symbolication, no
-- grouping UI, no alerting. It is a table a person can query when a rider
-- says "it closed again", and that is a large improvement on nothing.

CREATE TABLE client_crash_reports (
    id           BIGSERIAL PRIMARY KEY,

    -- WHICH APP, from the token and the request, not from a claim the body
    -- makes about itself.
    app          VARCHAR(16)  NOT NULL,

    -- WHO, AS THE SERVER KNOWS THEM. Both nullable and both set from the
    -- authenticated principal - never from the body. A crash report that
    -- could name its own reporter would let any signed-in account write
    -- entries against somebody else.
    customer_id  BIGINT       REFERENCES customers(id) ON DELETE SET NULL,
    worker_id    BIGINT       REFERENCES delivery_partners(id) ON DELETE SET NULL,

    -- WHICH BUILD. Without this a report is close to useless: "it crashes"
    -- against an unknown version cannot be matched to a fix. build_sha is the
    -- same value the Profile screen shows, so a rider reading it aloud and a
    -- row in here can be lined up.
    app_version  VARCHAR(32),
    build_sha    VARCHAR(40),
    platform     VARCHAR(32),

    -- FATAL means it escaped every catch in the app; not fatal means the
    -- framework caught it and the app carried on with a broken widget. Both
    -- are worth having and they are not the same severity.
    fatal        BOOLEAN      NOT NULL DEFAULT TRUE,

    message      VARCHAR(500) NOT NULL,

    -- TRUNCATED BY THE SERVER before it lands here. A stack trace arrives
    -- from a phone, and an unbounded TEXT column written by a crash-looping
    -- app is a way to fill a disk.
    stack        TEXT,

    reported_at  TIMESTAMP    NOT NULL DEFAULT now()
);

-- The two questions anyone actually asks: what broke recently, and is this
-- the same thing that broke last week.
CREATE INDEX idx_crash_reports_reported_at ON client_crash_reports (reported_at DESC);
CREATE INDEX idx_crash_reports_app_reported ON client_crash_reports (app, reported_at DESC);
