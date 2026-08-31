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
@SpringBootTest
class StaffRolePermissionsTest {

    @Autowired private CustomerRepository customerRepository;

    @Test
    @DisplayName("ADMIN keeps every permission - nobody loses access they had yesterday")
    void adminKeepsEverything() {
        // THE GUARANTEE THIS WHOLE CHANGE RESTS ON. Every staff account in
        // the shop today is an ADMIN and can reach all forty-one staff-gated
        // routes. If this ever fails, the change has quietly demoted real
        // people, and they will find out when something they do daily starts
        // returning 403.
        assertThat(RolePermissions.forRole(Role.ADMIN))
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(AdminPermission.class));
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
        // Guards against a future edit granting a role something ADMIN itself
        // does not have, which would make ADMIN no longer the superset the
        // rest of this design assumes.
        Set<AdminPermission> admin = RolePermissions.forRole(Role.ADMIN);
        for (Role role : Role.values()) {
            assertThat(admin)
                    .as("%s must not exceed ADMIN", role)
                    .containsAll(RolePermissions.forRole(role));
        }
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
