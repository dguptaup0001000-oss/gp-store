package com.gpstore.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

public class OrderNumberGenerator {

    private static final AtomicInteger counter = new AtomicInteger(1);

    public static String generate() {

        String date = LocalDate.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        int sequence = counter.getAndIncrement();

        return "GP" + date + String.format("%04d", sequence);
    }
}