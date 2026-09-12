package com.gpstore.security;

import com.gpstore.entity.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE ROLE MODEL, IN THE OWNER'S OWN WORDS.
 *
 * <p>Stated by the person who owns GP-STORE, and treated here as the source of
 * truth rather than as something inferred from whatever the code happened to
 * do:
 *
 * <blockquote>
 * app admin is super admin and shop owner is shop admin who manage shop and
 * their own worker. super admin manage whole app, it's taken a complete
 * decision regarding everything.
 * </blockquote>
 *
 * <p>WHY THIS FILE EXISTS SEPARATELY FROM {@code StaffRolePermissionsTest}.
 * That test checks the permission wiring - which role holds which constant.
 * This one states the BUSINESS RULE those constants are supposed to add up to,
 * so a future edit that is locally reasonable but violates the model fails
 * against the sentence above rather than against an implementation detail.
 *
 * <p>The model was NOT what the code did. SUPER_ADMIN and ADMIN held
 * byte-identical permission sets, which broke it in both directions at once:
 * the shop owner could read the whole marketplace's metrics, and the app admin
 * had no cross-shop scope at all - {@code TenantResolver} resolved the person
 * who owns the platform to a single shop, and threw outright on an owner
 * account with no shop membership.
 */
@DisplayName("The GP-STORE role model")
class TheGpStoreRoleModelTest {

    private static Set<AdminPermission> of(Role role) {
        return RolePermissions.forRole(role);
    }

    @Nested
    @DisplayName("app admin (SUPER_ADMIN) manages the whole app")
    class AppAdmin {

        @Test
        @DisplayName("takes a complete decision regarding everything")
        void holdsEveryPermissionThereIs() {
            // "complete decision regarding everything" is not a slogan here -
            // it is allOf(). The platform owner is the one role that should
            // gain whatever permission is added to the enum next, which is why
            // this is the only set in the application written as allOf rather
            // than as a subtraction.
            assertThat(of(Role.SUPER_ADMIN))
                    .as("the app admin must hold every permission in the system")
                    .containsExactlyInAnyOrder(AdminPermission.values());
        }

        @Test
        @DisplayName("spans every shop rather than being trapped in one")
        void carriesTheCrossShopScope() {
            // TenantResolver grants marketplace-wide scope on PLATFORM_ADMIN
            // and lets that holder name ANY shop in select(). Without it the
            // app admin is resolved to a single shop like a shopkeeper - which
            // is exactly the defect this model corrected.
            assertThat(of(Role.SUPER_ADMIN))
                    .as("without PLATFORM_ADMIN the app admin cannot see across shops at all")
                    .contains(AdminPermission.PLATFORM_ADMIN);
        }

        @Test
        @DisplayName("sees the marketplace's own numbers")
        void readsPlatformObservability() {
            assertThat(of(Role.SUPER_ADMIN)).contains(AdminPermission.PLATFORM_OBSERVABILITY);
        }
    }

    @Nested
    @DisplayName("shop admin (ADMIN) manages their shop and their own workers")
    class ShopAdmin {

        @Test
        @DisplayName("runs their shop: catalogue, stock, orders, money")
        void runsTheirOwnShop() {
            assertThat(of(Role.ADMIN)).contains(
                    AdminPermission.CATALOG_MANAGE,
                    AdminPermission.INVENTORY_MANAGE,
                    AdminPermission.ORDERS_MANAGE,
                    AdminPermission.PAYMENTS_REFUND,
                    AdminPermission.COUPONS_MANAGE);
        }

        @Test
        @DisplayName("manages their own workers")
        void managesTheirOwnRiders() {
            // /api/admin/workers/** is gated on DELIVERY_MANAGE, and the
            // roster it returns carries the shop filter - so "their own"
            // is enforced by scope, not by the permission alone.
            // CrossTenantApiAccessTest proves the roster half.
            assertThat(of(Role.ADMIN))
                    .as("a shop owner who cannot manage their riders cannot run deliveries")
                    .contains(AdminPermission.DELIVERY_MANAGE, AdminPermission.DELIVERY_VIEW);
        }

        @Test
        @DisplayName("is never the app admin, however convenient that would be")
        void isNotTheAppAdmin() {
            // The three permissions that separate running a shop from running
            // the marketplace. Each one held by a shop owner would be a
            // different kind of leak: a scope spanning every merchant, every
            // merchant's traffic, or every merchant's shelf.
            assertThat(of(Role.ADMIN)).doesNotContain(
                    AdminPermission.PLATFORM_ADMIN,
                    AdminPermission.PLATFORM_OBSERVABILITY,
                    AdminPermission.CATALOG_DEFINE);

            assertThat(of(Role.SUPER_ADMIN))
                    .as("the app admin and the shop admin have collapsed back into one role")
                    .hasSizeGreaterThan(of(Role.ADMIN).size());
        }
    }

    @Nested
    @DisplayName("PLATFORM_ADMIN is not a second authority")
    class NoSecondAuthority {

        @Test
        @DisplayName("grants nothing beyond SUPER_ADMIN, ever")
        void isExactlyTheAppAdmin() {
            // The owner's decision: SUPER_ADMIN is the single highest role and
            // PLATFORM_ADMIN is not a separate business role. It survives only
            // because customers.role is a string under a CHECK constraint that
            // V50 taught to accept it - deleting the constant would make
            // Role.valueOf throw on any row still holding it.
            //
            // EQUALITY IN BOTH DIRECTIONS. "Grants no more" alone would allow
            // it to quietly become a weaker role, which is the same divergence
            // in the other direction and just as much a second authority.
            assertThat(of(Role.PLATFORM_ADMIN))
                    .as("PLATFORM_ADMIN has drifted from SUPER_ADMIN and is a second authority "
                            + "again - it must be an alias, not a level")
                    .isEqualTo(of(Role.SUPER_ADMIN));
        }

        @Test
        @DisplayName("the shop admin is still nothing like either of them")
        void theShopAdminIsUnaffected() {
            // Collapsing the two platform names must not quietly widen the
            // shopkeeper, which is the mistake this whole model exists to stop.
            assertThat(of(Role.ADMIN)).doesNotContain(
                    AdminPermission.PLATFORM_ADMIN,
                    AdminPermission.PLATFORM_OBSERVABILITY,
                    AdminPermission.CATALOG_DEFINE);
        }
    }

    @Nested
    @DisplayName("worker (DELIVERY_BOY) delivers, and administers nothing")
    class Worker {

        @Test
        @DisplayName("holds no administrative permission at all")
        void holdsNothingAdministrative() {
            // A rider's access to their own deliveries comes from
            // ROLE_DELIVERY_BOY on the specific delivery rules, never from an
            // admin permission. WorkerDeliveryStatusTest proves they cannot
            // reach another worker's delivery even inside their own shop.
            assertThat(of(Role.DELIVERY_BOY))
                    .as("a rider with any admin permission is a rider who can administer a shop")
                    .isEmpty();
        }

        @Test
        @DisplayName("a customer holds nothing either")
        void customersHoldNothing() {
            assertThat(of(Role.CUSTOMER)).isEmpty();
        }
    }
}
