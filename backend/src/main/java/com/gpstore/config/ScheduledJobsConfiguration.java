package com.gpstore.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduling is disabled only for the bounded marketplace seed CLI profile. */
@Configuration
@EnableScheduling
@Profile("!marketplace-test-data-cli")
public class ScheduledJobsConfiguration { }
