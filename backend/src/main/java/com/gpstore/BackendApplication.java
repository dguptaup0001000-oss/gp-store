package com.gpstore;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
@EnableScheduling
@EnableCaching
public class BackendApplication {

	public static void main(String[] args) {
		// Force UTC regardless of the host machine's/container's local timezone.
		// This must run before SpringApplication.run() so every downstream
		// component (JDBC driver, Hibernate, scheduled jobs, java.util.Date
		// formatting) sees UTC as the default from the very first line.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(BackendApplication.class, args);
	}

}
