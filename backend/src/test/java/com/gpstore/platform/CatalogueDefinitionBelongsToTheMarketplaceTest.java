package com.gpstore.platform;

import com.gpstore.security.AdminPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who may write the catalogue every shop sells from - the rule itself, in one
 * place, without a database.
 *
 * <p>WHY NOT THROUGH HTTP. The end-to-end versions of these assertions run
 * against a shared test database, and the rule reads the number of shops in
 * it - so whether "a shop admin may define the catalogue" came back true
 * depended on whether some other test class had opened a second shop first.
 * That is a test whose result is decided by alphabetical order, and it had
 * started failing that way.
 *
 * <p>The rule has three inputs and they are all named here.
 */
@DisplayName("Defining the shared catalogue is the platform's job wherever there is more than one shop")
class CatalogueDefinitionBelongsToTheMarketplaceTest {

    @Test
    @DisplayName("one shop: the shopkeeper IS the platform, and keeps catalogue editing")
    void oneShopKeepsIts() {
        assertTrue(rule(PlatformMode.SINGLE_SHOP, 1).mayDefineCatalogue(
                        staffHolding(AdminPermission.CATALOG_MANAGE)),
                "the kirana that is trading today must still be able to add the atta it just "
                        + "bought - taking catalogue editing away from a single shop would "
                        + "break the only shop there is");
    }

    @Test
    @DisplayName("a marketplace: a shopkeeper may not write the shared catalogue")
    void aMarketplaceDoesNot() {
        assertFalse(rule(PlatformMode.MULTI_SHOP_PRODUCTION, 2).mayDefineCatalogue(
                        staffHolding(AdminPermission.CATALOG_MANAGE)),
                "one merchant would be editing the departments and products every other "
                        + "merchant sells from");
    }

    @Test
    @DisplayName("two shops but the mode says one: the data wins, because it fails safe")
    void theDataWinsOverTheFlag() {
        // A deployment carrying a second shop IS a marketplace whatever the
        // environment variable says. Believing the flag would hand one
        // merchant the other's catalogue on the strength of a missed config
        // change - the same failure the platform.mode default was fixed for.
        assertFalse(rule(PlatformMode.SINGLE_SHOP, 2).mayDefineCatalogue(
                        staffHolding(AdminPermission.CATALOG_MANAGE)),
                "the mode said one shop and the database held two; the rule must take the "
                        + "stricter reading");
    }

    @Test
    @DisplayName("the platform owner defines the catalogue on any deployment")
    void theOwnerAlwaysMay() {
        for (PlatformMode mode : PlatformMode.values()) {
            assertTrue(rule(mode, 7).mayDefineCatalogue(
                            staffHolding(AdminPermission.CATALOG_DEFINE)),
                    "the platform owner lost the catalogue in " + mode);
        }
    }

    @Test
    @DisplayName("nobody unauthenticated defines anything")
    void anonymousMayNot() {
        assertFalse(rule(PlatformMode.SINGLE_SHOP, 1).mayDefineCatalogue(null));
    }

    // ------------------------------------------------------------------

    private static CatalogDefinitionAuthorization rule(PlatformMode mode, long shopCount) {
        PlatformProperties platform = mock(PlatformProperties.class);
        when(platform.getMode()).thenReturn(mode);
        ShopRepository shops = mock(ShopRepository.class);
        when(shops.countByDeletedAtIsNull()).thenReturn(shopCount);
        return new CatalogDefinitionAuthorization(platform, shops);
    }

    private static Authentication staffHolding(AdminPermission permission) {
        List<GrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority(permission.authority()));
        return new UsernamePasswordAuthenticationToken("staff", null, authorities);
    }
}
