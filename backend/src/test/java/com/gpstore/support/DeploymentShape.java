package com.gpstore.support;

/**
 * The deployment a test means, said out loud instead of inherited.
 *
 * <p>WHY THESE WORDS EXIST. Dozens of tests build their fixtures by saving
 * entities straight through a repository - an order, its inventory, its
 * payment - with no HTTP request behind them and therefore no tenant scope on
 * the thread. That worked for one reason: {@code platform.mode} defaulted to
 * SINGLE_SHOP, so {@code TenantDefaults} stamped the deployment's only shop on
 * any shop-owned row that arrived without one.
 *
 * <p>That default was wrong for production - an unset environment variable
 * must not decide who a row belongs to - and it is now the marketplace, where
 * a row that names no shop is refused outright. The refusal is correct and is
 * the point of the change.
 *
 * <p>So a test whose SUBJECT is something else - refund arithmetic, checkout
 * concurrency, an outbox, a state machine - declares the shape of the
 * deployment it is describing, and goes on testing its subject. This is not a
 * way around the new rule: tests whose subject IS tenancy declare nothing and
 * run under the production default, which is how the marketplace behaviour is
 * covered (see AuthorizationDoesNotDependOnModeTest, MarketplaceIdentityTest,
 * EveryPublishedEndpointSweepTest and the merchant isolation tests).
 *
 * <p>A test that wants the marketplace AND writes rows directly does not use
 * this - it names the shop on the row, or wraps the fixture in a scope, which
 * is what a marketplace requires of every writer.
 */
public final class DeploymentShape {

    /** One shop, the way a single kirana runs GP-STORE. */
    public static final String SINGLE_SHOP = "platform.mode=SINGLE_SHOP";

    private DeploymentShape() {
    }
}
