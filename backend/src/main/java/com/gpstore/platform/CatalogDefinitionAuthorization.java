package com.gpstore.platform;

import com.gpstore.security.AdminPermission;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Who may change what a PRODUCT IS, as opposed to what a shop charges for it.
 *
 * TWO DIFFERENT ACTS THAT LOOK THE SAME FROM A FORM. Editing "Aashirvaad Atta
 * 5 kg" - its name, its pack size, its photo, its GST class - changes the row
 * every shop selling it points at. Editing its price changes one shop's own
 * row. The first is a platform act; the second is a shopkeeper's daily work.
 * Before the marketplace they were the same screen because there was one shop.
 *
 * THE RULE, IN ONE PLACE:
 *
 *   SINGLE_SHOP        whoever holds CATALOG_MANAGE. With one merchant the
 *                      shopkeeper IS the platform, and taking catalogue
 *                      editing away from them would break the shop that is
 *                      actually trading today. Nothing changes.
 *
 *   MULTI_SHOP_*       CATALOG_DEFINE, which only PLATFORM_ADMIN holds. One
 *                      merchant must not be able to rename, re-categorise or
 *                      withdraw an item that other merchants are selling.
 *
 * WHY IT IS AN AuthorizationManager AND NOT A CHECK INSIDE THE SERVICE. It
 * runs on the route, before the controller, so a service reachable from two
 * places cannot be reached through the unguarded one - and SecurityConfig
 * cannot express a mode-dependent rule any other way.
 */
@Component
public class CatalogDefinitionAuthorization
        implements AuthorizationManager<RequestAuthorizationContext> {

    private final PlatformProperties platform;

    public CatalogDefinitionAuthorization(PlatformProperties platform) {
        this.platform = platform;
    }

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication,
                                       RequestAuthorizationContext context) {
        return new AuthorizationDecision(mayDefineCatalogue(authentication.get()));
    }

    /** Exposed so services and tests can ask the same question the route asks. */
    public boolean mayDefineCatalogue(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (holds(authentication, AdminPermission.CATALOG_DEFINE)) {
            return true;
        }
        return !platform.getMode().isMultiShop()
                && holds(authentication, AdminPermission.CATALOG_MANAGE);
    }

    private static boolean holds(Authentication authentication, AdminPermission permission) {
        String required = permission.authority();
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            if (required.equals(granted.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
