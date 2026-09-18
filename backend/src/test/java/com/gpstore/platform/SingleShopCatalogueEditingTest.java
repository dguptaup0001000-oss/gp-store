package com.gpstore.platform;

import com.gpstore.security.WithStaff;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The half of the marketplace change that must change nothing.
 *
 * <p>MOVED OUT OF MerchantShopLifecycleTest, AND THE MOVE IS THE POINT. This
 * assertion is about a deployment with ONE shop, where the shopkeeper is also
 * the platform and catalogue editing has always been theirs. Its old home
 * declares no {@code platform.mode}, so since the default became the
 * marketplace it was asking a marketplace whether a merchant may write the
 * shared catalogue - where the answer is no, correctly, and the test was
 * failing for the one reason that does not mean a regression.
 *
 * <p>Here it names the deployment it is talking about, and goes on protecting
 * the shop that is actually trading today: take catalogue editing away from a
 * single kirana and they cannot add the atta they just bought.
 *
 * <p>The marketplace half - a merchant is refused, the platform is not - is
 * asserted by SharedCatalogueMaintenanceIsThePlatformsTest and by
 * AMerchantAddsWhatTheySellTest, both of which run under the production
 * default.
 */
@SpringBootTest(properties = {
        com.gpstore.support.DeploymentShape.SINGLE_SHOP,
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@DisplayName("Under one shop, the shopkeeper still edits the catalogue")
class SingleShopCatalogueEditingTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private final String tag = "sscat" + System.nanoTime();

    @Test
    @WithStaff
    @DisplayName("the trading shop's own admin can still create a category")
    void theShopkeeperStillEditsTheCatalogue() throws Exception {
        int status = mockMvc.perform(post("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lifecycle category " + tag
                                + "\",\"active\":true,\"gstRate\":5}"))
                .andReturn().getResponse().getStatus();

        assertNotEquals(403, status,
                "the trading shop's own admin was refused a catalogue write they have "
                        + "always had");
        jdbc.update("DELETE FROM categories WHERE name = ?", "Lifecycle category " + tag);
    }
}
