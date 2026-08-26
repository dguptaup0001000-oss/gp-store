package com.gpstore.config;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Flyway checksums of already-applied scripts are immutable. A comment-only
 * edit of V19 in c09eeed changed the checksum from 78920826 (applied on
 * production) to 1847251676 and Flyway refused to boot the API after Redis
 * was restored.
 *
 * Algorithm matches Flyway 11: CRC32 of each line's UTF-8 bytes, without
 * the line terminator (BufferedReader.readLine()).
 */
class FlywayProductionChecksumTest {

    @Test
    void v19MatchesChecksumAppliedOnProduction() throws Exception {
        assertEquals(78920826, flywayChecksum("/db/migration/V19__add_delivery_territories.sql"));
    }

    static int flywayChecksum(String classpath) throws Exception {
        var in = FlywayProductionChecksumTest.class.getResourceAsStream(classpath);
        assertNotNull(in, classpath);
        CRC32 crc = new CRC32();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                crc.update(line.getBytes(StandardCharsets.UTF_8));
            }
        }
        return (int) crc.getValue();
    }
}
