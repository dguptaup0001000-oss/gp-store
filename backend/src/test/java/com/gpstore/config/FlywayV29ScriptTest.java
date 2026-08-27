package com.gpstore.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlywayV29ScriptTest {

    @Test
    void v29WidensCouponTypeAndSeedsFreeDel10() throws Exception {
        String sql = new ClassPathResource("db/migration/V29__delivery_flat_coupon.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(sql.contains("DELIVERY_FLAT"));
        assertTrue(sql.contains("FREEDEL10"));
        assertTrue(sql.contains("coupons_discount_type_check"));
        assertFalse(sql.toUpperCase().contains("CREATE EXTENSION"),
                "Do not CREATE EXTENSION in V29+; the production role may not be allowed to");
    }
}
