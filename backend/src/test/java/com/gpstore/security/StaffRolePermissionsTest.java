package com.gpstore.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.gpstore.entity.Customer;
import com.gpstore.entity.Role;
import com.gpstore.repository.CustomerRepository;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The permission model itself.
 *
 * <p>The endpoint-by-endpoint checks live in StaffRoleAuthorizationTest. This
 * file pins the two things that decide whether that one is even meaningful:
 * what each role is granted, and whether the DATABASE will store the role at
 * all.
 */
@SpringBootTest(properties = {
        // NO LIVE OUTBOX WORKER. A running drain turns committed work into
        // auto-assigned deliveries against whichever rider is available, and
        // Spring caches this context and never closes it - so the worker
        // outlives the class and keeps assigning while later classes are
        // asserting. That is how TerritoryDispatchTest failed with
        // "expected: <22> but was: <23>": a stray assignment gave one of two
        // deliberately-tied riders a live order and the tie broke the other
        // way.
        //
        // Nothing in this class tests the outbox or waits on an async side
        // effect, so the drain has no purpose here beyond causing that.
        // OutboxDurabilityTest, which does test it, keeps a live worker.
        "outbox.drain-interval-ms=3600000"
})
class StaffRolePermissionsTest {

    @Autowired private CustomerRepository customerRepository;

    @Test
    @DisplayName("ADMIN keeps every permission it had - nobody loses access they had yesterday")
    void adminKeepsEverything() {
        // THE GUARANTEE THIS WHOLE CHANGE RESTS ON. Every staff account in
        // the shop today is an ADMIN and can reach all forty-one staff-gated
        // routes. If this ever fails, the change has quietly demoted real
        // people, and they will find out when something they do daily starts
        // returning 403.
        //
        // PINNED BY NAME, NOT BY allOf(). This used to assert equality with
        // every constant in the enum, which reads like a stronger statement
        // and is a weaker one: it says "ADMIN gets whatever anybody adds
        // next", so the day PLATFORM_ADMIN was added every shopkeeper in the
        // marketplace would have silently become a platform operator with a
        // scope spanning every merchant, and this test would have gone green.
        // The list below is the set ADMIN actually held, written out, so
        // taking one away fails here and adding a new one is a decision
        // somebody has to make on purpose.
        assertThat(RolePermissions.forRole(Role.ADMIN)).containsExactlyInAnyOrder(
                AdminPermission.ORDERS_VIEW,
                AdminPermission.ORDERS_MANAGE,
                AdminPermission.PAYMENTS_VIEW,
                AdminPermission.PAYMENTS_MANAGE,
                AdminPermission.PAYMENTS_REFUND,
                AdminPermission.CATALOG_VIEW,
                AdminPermission.CATALOG_MANAGE,
                AdminPermission.INVENTORY_MANAGE,
                AdminPermission.COUPONS_MANAGE,
                AdminPermission.CUSTOMERS_VIEW,
                AdminPermission.CUSTOMERS_MANAGE,
                AdminPermission.DELIVERY_VIEW,
                AdminPermission.DELIVERY_MANAGE,
                AdminPermission.REVIEWS_MODERATE,
                AdminPermission.BROADCAST_SEND,
                AdminPermission.ANALYTICS_VIEW,
                AdminPermission.AUDIT_VIEW,
                AdminPermission.SYSTEM_ADMIN);
    }

    @Test
    @DisplayName("a shop owner is not a platform operator")
    void adminIsNotPlatformAdmin() {
        // The single most dangerous line in the marketplace design. Every
        // staff account that exists today is an ADMIN; if ADMIN carried
        // PLATFORM_ADMIN, TenantResolver would hand every one of them a scope
        // spanning every merchant on the platform.
        for (Role role : Role.values()) {
            boolean expected = role == Role.PLATFORM_ADMIN;
            assertThat(RolePermissions.forRole(role).contains(AdminPermission.PLATFORM_ADMIN))
                    .as("PLATFORM_ADMIN for %s", role)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("a shop role cannot write the shared catalogue definition")
    void shopRolesCannotDefineTheCatalogue() {
        // CATALOG_MANAGE is a shopkeeper's own price and stock. CATALOG_DEFINE
        // is what a product IS, shared by every shop selling it - so one
        // merchant holding it would be editing every other merchant's shelf.
        for (Role role : Role.values()) {
            boolean expected = role == Role.PLATFORM_ADMIN;
            assertThat(RolePermissions.forRole(role).contains(AdminPermission.CATALOG_DEFINE))
                    .as("CATALOG_DEFINE for %s", role)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("SUPER_ADMIN is at least everything ADMIN is")
    void superAdminIsNotWeakerThanAdmin() {
        assertThat(RolePermissions.forRole(Role.SUPER_ADMIN))
                .containsAll(RolePermissions.forRole(Role.ADMIN));
    }

    @Test
    @DisplayName("a customer and a rider get no admin permissions at all")
    void nonStaffGetNothing() {
        // A rider's access to their own deliveries comes from
        // ROLE_DELIVERY_BOY on the specific rules that allow it. If it ever
        // came from this map instead, every rider would gain whatever those
        // permissions unlock everywhere else.
        assertThat(RolePermissions.forRole(Role.CUSTOMER)).isEmpty();
        assertThat(RolePermissions.forRole(Role.DELIVERY_BOY)).isEmpty();
        assertThat(RolePermissions.isStaff(Role.CUSTOMER)).isFalse();
        assertThat(RolePermissions.isStaff(Role.DELIVERY_BOY)).isFalse();
    }

    @Test
    @DisplayName("an unknown or missing role fails closed")
    void unknownRoleGetsNothing() {
        // A token carrying a role this build has never heard of is either
        // from a newer deployment or forged. Neither gets the shop.
        assertThat(RolePermissions.forRoleName("WAREHOUSE_GOD")).isEmpty();
        assertThat(RolePermissions.forRoleName(null)).isEmpty();
        assertThat(RolePermissions.forRoleName("")).isEmpty();
        assertThat(RolePermissions.forRoleName("  ")).isEmpty();
        assertThat(RolePermissions.forRole(null)).isEmpty();
    }

    @Test
    @DisplayName("role names are matched case- and whitespace-insensitively")
    void roleNameLookupIsForgiving() {
        assertThat(RolePermissions.forRoleName(" admin "))
                .isEqualTo(RolePermissions.forRole(Role.ADMIN));
    }

    @Test
    @DisplayName("only the shop owner can reach the system surface")
    void systemAdminIsNarrow() {
        // /actuator, the API docs, bulk catalogue seeding, and anything new
        // under /api/admin/** that nobody has classified yet. An unclassified
        // route should be reachable by the owner, not by whoever is on shift.
        for (Role role : Role.values()) {
            boolean expected = role == Role.ADMIN || role == Role.SUPER_ADMIN;
            assertThat(RolePermissions.forRole(role).contains(AdminPermission.SYSTEM_ADMIN))
                    .as("SYSTEM_ADMIN for %s", role)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("refunds are narrower than taking money in")
    void refundIsNarrowerThanCollect() {
        // Confirming a UPI transfer and sending money back are not the same
        // trust. The counter staff can do the first and not the second.
        assertThat(RolePermissions.forRole(Role.ORDER_MANAGER))
                .contains(AdminPermission.PAYMENTS_MANAGE)
                .doesNotContain(AdminPermission.PAYMENTS_REFUND);

        assertThat(RolePermissions.forRole(Role.SUPPORT))
                .contains(AdminPermission.PAYMENTS_VIEW)
                .doesNotContain(AdminPermission.PAYMENTS_MANAGE,
                        AdminPermission.PAYMENTS_REFUND);
    }

    @Test
    @DisplayName("support can look but not change")
    void supportIsReadOnlyExceptModeration() {
        Set<AdminPermission> support = RolePermissions.forRole(Role.SUPPORT);
        assertThat(support).doesNotContain(
                AdminPermission.ORDERS_MANAGE,
                AdminPermission.CATALOG_MANAGE,
                AdminPermission.INVENTORY_MANAGE,
                AdminPermission.CUSTOMERS_MANAGE,
                AdminPermission.DELIVERY_MANAGE,
                AdminPermission.COUPONS_MANAGE,
                AdminPermission.BROADCAST_SEND,
                AdminPermission.SYSTEM_ADMIN);
        // The one thing they may take down.
        assertThat(support).contains(AdminPermission.REVIEWS_MODERATE);
    }

    @Test
    @DisplayName("every new role is a subset of ADMIN")
    void everyRoleIsASubsetOfAdmin() {
        // Guards against a future edit granting a SHOP role something ADMIN
        // itself does not have, which would make ADMIN no longer the superset
        // the rest of this design assumes.
        //
        // PLATFORM_ADMIN IS EXCLUDED BECAUSE IT IS NOT A SHOP ROLE. It is the
        // other axis: it governs merchants, shop lifecycle and the shared
        // catalogue, and it is deliberately NARROWER than ADMIN inside any one
        // shop - it can read an order to settle a dispute but cannot advance
        // it, refund it, or touch that shop's roster. Asserting it as a subset
        // of a shopkeeper's permissions would be asserting that running the
        // market is a smaller version of running a shop, which is exactly the
        // conflation this role exists to end.
        Set<AdminPermission> admin = RolePermissions.forRole(Role.ADMIN);
        for (Role role : Role.values()) {
            if (role == Role.PLATFORM_ADMIN) {
                continue;
            }
            assertThat(admin)
                    .as("%s must not exceed ADMIN", role)
                    .containsAll(RolePermissions.forRole(role));
        }

        // ...and the part of that claim that IS about shops, asserted rather
        // than waved at: inside a shop, the platform operator can do strictly
        // less than the shopkeeper.
        Set<AdminPermission> platform = RolePermissions.forRole(Role.PLATFORM_ADMIN);
        assertThat(platform)
                .as("a platform operator must not be able to work a shop's orders or money")
                .doesNotContain(AdminPermission.ORDERS_MANAGE,
                        AdminPermission.PAYMENTS_MANAGE,
                        AdminPermission.PAYMENTS_REFUND,
                        AdminPermission.DELIVERY_MANAGE,
                        AdminPermission.COUPONS_MANAGE,
                        AdminPermission.CUSTOMERS_MANAGE,
                        AdminPermission.SYSTEM_ADMIN);
    }

    @Test
    @DisplayName("every permission is granted to somebody")
    void noOrphanPermissions() {
        // A permission nobody holds is a route nobody can reach. If one turns
        // up here it is either a typo in RolePermissions or a rule that
        // should have been deleted.
        for (AdminPermission permission : AdminPermission.values()) {
            boolean held = false;
            for (Role role : Role.values()) {
                if (RolePermissions.forRole(role).contains(permission)) {
                    held = true;
                    break;
                }
            }
            assertThat(held).as("%s is granted to no role", permission).isTrue();
        }
    }

    @Test
    @DisplayName("the authority string is the contract SecurityConfig matches on")
    void authorityStringIsPinned() {
        // SecurityConfig matches these exact strings. Renaming a constant
        // without renaming its rule silently unguards the route.
        assertThat(AdminPermission.PAYMENTS_REFUND.authority()).isEqualTo("PERM_PAYMENTS_REFUND");
        assertThat(AdminPermission.SYSTEM_ADMIN.authority()).isEqualTo("PERM_SYSTEM_ADMIN");
        for (AdminPermission permission : AdminPermission.values()) {
            assertThat(permission.authority()).startsWith("PERM_");
        }
    }

    @Test
    @DisplayName("the database actually stores every role - the CHECK constraint is real")
    void everyRoleCanBePersisted() {
        // THE FAILURE THIS CATCHES IS INVISIBLE OTHERWISE. Hibernate wrote a
        // CHECK constraint listing the three original enum values, and
        // ddl-auto=validate does not inspect check constraints - so without
        // V32__staff_roles.sql the application starts happily and then throws
        // on the first attempt to save a MANAGER. Saving one of each role here
        // is the only thing that proves the migration ran.
        for (Role role : Role.values()) {
            Customer staff = new Customer();
            staff.setFullName("role-check-" + role.name());
            staff.setEmail("role-check-" + role.name().toLowerCase()
                    + "-" + System.nanoTime() + "@example.test");
            staff.setMobileNumber(String.valueOf(9200000000L + (System.nanoTime() % 700000000L)));
            staff.setRole(role);
            staff.setEnabled(true);
            staff.setActive(true);
            staff.setVerified(true);

            Customer saved = customerRepository.saveAndFlush(staff);
            assertThat(saved.getRole()).as("persisting %s", role).isEqualTo(role);
        }
    }
}
