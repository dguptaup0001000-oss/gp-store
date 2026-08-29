package com.gpstore.controller;

import com.gpstore.dto.AuthRequest;
import com.gpstore.dto.AuthResponse;
import com.gpstore.dto.ChangePasswordRequest;
import com.gpstore.dto.PasswordResetCompleteRequest;
import com.gpstore.dto.PasswordResetTokenResponse;
import com.gpstore.dto.PhoneOtpRequest;
import com.gpstore.dto.PhoneOtpVerifyRequest;
import com.gpstore.dto.RefreshRequest;
import com.gpstore.dto.RegisterRequest;
import com.gpstore.dto.ResetPasswordWithOtpRequest;
import com.gpstore.dto.SendOtpRequest;
import com.gpstore.dto.VerifyOtpRequest;
import com.gpstore.security.CurrentUser;
import com.gpstore.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final CurrentUser currentUser;

    public AuthController(AuthService authService, CurrentUser currentUser) {
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody AuthRequest request) {
        return authService.login(request);
    }

    // Sends an OTP to this phone number - works for both new and existing
    // customers (the actual account is only created/found on verify).
    @PostMapping("/otp/send")
    public Map<String, String> sendOtp(@Valid @RequestBody SendOtpRequest request) {
        authService.sendLoginOtp(request.identity());
        return Map.of("message", "OTP sent");
    }

    // Verifies the OTP and logs in - auto-creating a bare account if this
    // identity has never been seen before (same pattern as
    // Swiggy/Zomato's OTP-only signup).
    @PostMapping("/otp/verify")
    public AuthResponse verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        return authService.verifyOtpAndAuthenticate(request.identity(), request.getOtp());
    }

    @PostMapping("/otp/login/request")
    public Map<String, String> requestLoginOtp(@Valid @RequestBody PhoneOtpRequest request) {
        return Map.of("message", authService.requestLoginOtp(request.identity()));
    }

    @PostMapping("/otp/login/verify")
    public AuthResponse verifyLoginOtp(@Valid @RequestBody PhoneOtpVerifyRequest request) {
        return authService.verifyOtpAndAuthenticate(request.identity(), request.getOtp());
    }

    @PostMapping("/password-reset/request")
    public Map<String, String> requestPasswordReset(@Valid @RequestBody PhoneOtpRequest request) {
        return Map.of("message", authService.requestPasswordResetOtp(request.identity()));
    }

    @PostMapping("/password-reset/verify")
    public PasswordResetTokenResponse verifyPasswordReset(@Valid @RequestBody PhoneOtpVerifyRequest request) {
        return authService.verifyPasswordResetOtp(request.identity(), request.getOtp());
    }

    @PostMapping("/password-reset/complete")
    public Map<String, String> completePasswordReset(@Valid @RequestBody PasswordResetCompleteRequest request) {
        authService.completePasswordReset(request.getResetToken(), request.getNewPassword());
        return Map.of("message", "Password reset - you can now log in with your new password");
    }

    // Exchanges a refresh token for a new access token + a new refresh token
    // (rotation). No access token/auth header needed here - the refresh token
    // itself is the credential.
    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.getRefreshToken());
    }

    // Requires being logged in already (see SecurityConfig) - for a
    // customer who knows their current password (or has none yet, an
    // OTP-only account setting one up for the first time) and wants to
    // change/set it.
    @PutMapping("/change-password")
    public Map<String, String> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(currentUser.customerId(), request.getCurrentPassword(), request.getNewPassword());
        return Map.of("message", "Password updated");
    }

    // No auth required (this IS the "I forgot my password" flow) - proves
    // identity via OTP to the account's own mobile number instead. There's
    // no email infrastructure in this system, so OTP is the real,
    // available verification mechanism, not a fallback choice.
    @PostMapping("/reset-password-with-otp")
    public Map<String, String> resetPasswordWithOtp(@Valid @RequestBody ResetPasswordWithOtpRequest request) {
        authService.resetPasswordWithOtp(request.identity(), request.getOtp(), request.getNewPassword());
        return Map.of("message", "Password reset - you can now log in with your new password");
    }

    // Logs out of just this device/session.
    @PostMapping("/logout")
    public Map<String, String> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.getRefreshToken());
        return Map.of("message", "Logged out successfully");
    }

    // Logs out of every device/session for this customer.
    @PostMapping("/logout-all")
    public Map<String, String> logoutAll() {
        authService.logoutAllSessions(currentUser.customerId());
        return Map.of("message", "Logged out of all sessions");
    }
}
