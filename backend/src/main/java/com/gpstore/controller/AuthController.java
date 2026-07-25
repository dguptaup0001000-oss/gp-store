package com.gpstore.controller;

import com.gpstore.dto.AuthRequest;
import com.gpstore.dto.AuthResponse;
import com.gpstore.dto.RefreshRequest;
import com.gpstore.dto.RegisterRequest;
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

    // Exchanges a refresh token for a new access token + a new refresh token
    // (rotation). No access token/auth header needed here - the refresh token
    // itself is the credential.
    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.getRefreshToken());
    }

    // Logs out of just this device/session.
    @PostMapping("/logout")
    public Map<String, String> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.getRefreshToken());
        return Map.of("message", "Logged out successfully");
    }

    // Logs out of every device/session for the currently logged-in customer.
    @PostMapping("/logout-all")
    public Map<String, String> logoutAll() {
        authService.logoutAllSessions(currentUser.customerId());
        return Map.of("message", "Logged out of all sessions");
    }
}
