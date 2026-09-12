package com.gpstore.config;

import com.gpstore.platform.BackgroundWorkScope;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Every scheduled task runs in a scope it declared.
 *
 * A CUSTOMIZER RATHER THAN A TaskScheduler BEAN, deliberately. Defining the
 * scheduler here would take ownership of its pool size, thread names and
 * shutdown behaviour away from Spring Boot's own configuration, and this
 * change is not about any of those - it is about one decorator. Boot applies
 * every ThreadPoolTaskSchedulerCustomizer to the scheduler it builds, so the
 * properties keep working and there is nothing to keep in step.
 *
 * WHAT THIS REPLACES: nine scheduled jobs, eight of which ran with no tenant
 * scope at all. Not one of them was wrong to span every shop - that is what a
 * sweep is for - but none of them said so, and the one place that distinction
 * mattered took the whole marketplace's order dispatch down. See
 * BackgroundWorkScope for the full account.
 *
 * It is applied here rather than on each job because a rule that has to be
 * remembered on every new @Scheduled method is a rule that will be forgotten
 * on one - and three of the nine already hid from a grep by writing the
 * annotation fully qualified.
 */
@Configuration
public class ScheduledWorkScopeConfig {

    @Bean
    public ThreadPoolTaskSchedulerCustomizer tenantScopedScheduledWork() {
        return scheduler -> scheduler.setTaskDecorator(BackgroundWorkScope.platformWide());
    }
}
