package com.gpstore.notify;

import com.gpstore.exception.BadRequestException;
import com.gpstore.security.CurrentUser;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where an app says "you can reach me here" and "you can't any more".
 *
 * <h2>The account is never in the body</h2>
 *
 * <p>Both routes take the account from the verified token and nothing else. A
 * client that could name the account it is registering for could subscribe
 * somebody else's device to its own orders, or silence a competitor's counter
 * phone - so the account is not a field, on purpose.
 *
 * <p>The app says which of the four apps it is, and that is not a security
 * claim: it decides which alerts this install is interested in, not which it is
 * allowed to have. What an install may actually be told is decided at dispatch
 * from live shop membership - see {@link NewOrderAlerts}.
 */
@RestController
@RequestMapping("/api/push")
public class PushRegistrationController {

    private final PushRegistrationService registrations;
    private final CurrentUser currentUser;

    public PushRegistrationController(PushRegistrationService registrations,
                                      CurrentUser currentUser) {
        this.registrations = registrations;
        this.currentUser = currentUser;
    }

    /** No account field. See the class comment. */
    public record RegisterRequest(String token, String app, String platform, String deviceId) {}

    public record ForgetRequest(String token) {}

    public record RegistrationView(Long id, String app, String platform, boolean enabled) {}

    @PostMapping("/registrations")
    public RegistrationView register(@RequestBody RegisterRequest request) {
        Long me = me();
        PushRegistration saved = registrations.remember(
                me,
                request.token(),
                PushApp.parse(request.app()),
                request.platform(),
                request.deviceId());
        // The token is not echoed. It went out once from the device that owns
        // it and there is no reason for it to travel back.
        return new RegistrationView(saved.getId(), saved.getApp(),
                saved.getPlatform(), Boolean.TRUE.equals(saved.getEnabled()));
    }

    /**
     * Called on sign-out, before the token is thrown away.
     *
     * <p>204 EITHER WAY. Whether a given token is on file is not something a
     * caller should be able to probe, and a sign-out that reports "there was
     * nothing to forget" is a sign-out that failed for no useful reason.
     */
    @DeleteMapping("/registrations")
    public ResponseEntity<Void> forget(@RequestBody ForgetRequest request) {
        registrations.forget(me(), request == null ? null : request.token());
        return ResponseEntity.noContent().build();
    }

    private Long me() {
        Long me = currentUser.customerId();
        if (me == null) {
            throw new BadRequestException("Sign in before registering a device.");
        }
        return me;
    }
}
