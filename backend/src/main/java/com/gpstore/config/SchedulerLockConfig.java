package com.gpstore.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Makes @Scheduled jobs safe to run on more than one backend instance.
 *
 * Without this, every instance runs every scheduled job independently and
 * simultaneously - e.g. two instances both finding the same stale UPI
 * payments and both trying to expire/restore-inventory for them at once.
 * ShedLock uses a plain DB row (see db/migration/V3__add_shedlock_table.sql)
 * as the coordination point: whichever instance grabs the lock row runs the
 * job; the others skip that run entirely. Correct today at one instance (the
 * lock is simply uncontested) and required the moment a second instance is
 * ever added - this is deliberately wired up now rather than left as a
 * "remember to do this later" the same way delivery auto-assignment was
 * before it caused a real bug.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(dataSource);
    }
}
