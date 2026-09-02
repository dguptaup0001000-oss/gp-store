package com.gpstore.security;

import com.gpstore.entity.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The same person can deliver for the shop and buy from it.
 *
 * THE BUG THIS CLOSES, and it was invisible until checkout. Granting a rider
 * their worker login set role = DELIVERY_BOY, which REPLACED CUSTOMER. Nothing
 * looked wrong: browsing and the cart are gated on being authenticated, so a
 * rider could sign in to the customer app, fill a basket and reach checkout -
 * and only there discover that the three payment routes require ROLE_CUSTOMER
 * and their own order could not be paid for.
 *
 * A shop with three staff is exactly where one person is both. The delivery
 * role now ADDS to being a customer instead of standing in for it.
 *
 * Pure functions, no Spring: this is about what a role means, and computing it
 * is the whole behaviour.
 */
@DisplayName("A delivery rider keeps their own customer account")
class RiderIsAlsoAShopperTest {

    @Test
    @DisplayName("a rider carries ROLE_CUSTOMER as well as ROLE_DELIVERY_BOY")
    void riderIsAlsoACustomer() {
        Set<String> authorities = RolePermissions.authorityNames(Role.DELIVERY_BOY);

        assertTrue(authorities.contains("ROLE_DELIVERY_BOY"),
                "The delivery job is what gets them into the worker app.");
        assertTrue(authorities.contains("ROLE_CUSTOMER"),
                "And this is what lets them pay for their own shopping. Without it "
                        + "checkout-session, verify and POST /api/payments all refuse.");
    }

    @Test
    @DisplayName("it grants nothing beyond shopping - a rider is still not staff")
    void riderGainsNoPermissions() {
        Set<String> authorities = RolePermissions.authorityNames(Role.DELIVERY_BOY);

        assertEquals(Set.of("ROLE_DELIVERY_BOY", "ROLE_CUSTOMER"), authorities,
                "ROLE_CUSTOMER only reaches routes acting on the caller's OWN cart, "
                        + "orders and payments. A single PERM_ authority here would be a "
                        + "rider with a console.");
        assertTrue(RolePermissions.forRole(Role.DELIVERY_BOY).isEmpty());
        assertFalse(RolePermissions.isStaff(Role.DELIVERY_BOY));
    }

    @Test
    @DisplayName("a plain shopper is unchanged")
    void customerIsUntouched() {
        assertEquals(Set.of("ROLE_CUSTOMER"), RolePermissions.authorityNames(Role.CUSTOMER));
    }

    @Test
    @DisplayName("staff do not silently become shoppers")
    void staffDoNotInheritCustomer() {
        // A shopkeeper buying from their own shop is a real thing and a
        // SEPARATE decision. Inheriting it from a helper would be deciding it
        // by accident, so every staff role is checked rather than assumed.
        for (Role role : Role.values()) {
            if (role == Role.CUSTOMER || role == Role.DELIVERY_BOY) {
                continue;
            }
            assertFalse(RolePermissions.authorityNames(role).contains("ROLE_CUSTOMER"),
                    role + " must not carry ROLE_CUSTOMER");
        }
    }

    @Test
    @DisplayName("every role still carries its own ROLE_ name and permissions")
    void everyRoleKeepsItsOwnAuthorities() {
        for (Role role : Role.values()) {
            Set<String> authorities = RolePermissions.authorityNames(role);
            assertTrue(authorities.contains("ROLE_" + role.name()), role.toString());
            RolePermissions.forRole(role).forEach(permission ->
                    assertTrue(authorities.contains(permission.authority()),
                            role + " lost " + permission));
        }
    }

    @Test
    @DisplayName("an unknown or missing role name gets nothing")
    void unknownRoleFailsClosed() {
        // A token carrying a role this build has never heard of is either from
        // a newer deployment or forged. Neither gets handed the shop.
        assertTrue(RolePermissions.authorityNamesForRoleName("WIZARD").isEmpty());
        assertTrue(RolePermissions.authorityNamesForRoleName(null).isEmpty());
        assertTrue(RolePermissions.authorityNamesForRoleName("   ").isEmpty());
    }

    @Test
    @DisplayName("a role name off a JWT resolves the same as the enum")
    void nameAndEnumAgree() {
        // JwtFilter takes the name; the test annotation takes the enum. They
        // must not be able to disagree.
        for (Role role : Role.values()) {
            assertEquals(RolePermissions.authorityNames(role),
                    RolePermissions.authorityNamesForRoleName(role.name().toLowerCase()),
                    role.toString());
        }
    }
}
