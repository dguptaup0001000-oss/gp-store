package com.gpstore.dto.response;

/**
 * Which login account, if any, a delivery partner can sign in with.
 *
 * A DTO rather than exposing DeliveryPartner.account, for two reasons. The
 * association is LAZY and this application runs with open-in-view=false, so a
 * getter that reached through it during Jackson serialisation would throw
 * LazyInitializationException on every roster listing. And the Customer it
 * points at carries a password hash, a role and an FCM token, none of which
 * an admin screen asking "can this rider log in?" has any business receiving.
 *
 * @param linked        whether a login account is attached at all
 * @param email         that account's email, or null when nothing is linked
 * @param canSignIn     whether the account can actually use the worker app's
 *                      email-and-password form. False for an OTP-only account
 *                      that has no password - which is what every partner
 *                      account created by the roster screen used to be, and
 *                      the reason this whole endpoint exists.
 */
public record WorkerLoginAccountView(boolean linked, String email, boolean canSignIn) {

    public static WorkerLoginAccountView none() {
        return new WorkerLoginAccountView(false, null, false);
    }
}
