-- Backs ShedLock (see config.SchedulerLockConfig): coordinates @Scheduled
-- jobs so that if this app ever runs as more than one instance, only ONE
-- instance actually executes a given scheduled job at a time, instead of
-- every instance running it redundantly and simultaneously. Table schema
-- is ShedLock's own standard/required layout for its JDBC provider - not a
-- domain table, don't add JPA entity mapping for it.
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
