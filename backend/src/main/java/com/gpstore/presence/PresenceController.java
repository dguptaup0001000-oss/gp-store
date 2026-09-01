package com.gpstore.presence;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live shop activity for the admin console.
 *
 * Under /api/admin/** so SecurityConfig's existing rule makes it
 * SYSTEM_ADMIN-only - who is in the shop right now is operational data, not
 * something a customer's token should be able to read.
 */
@RestController
@RequestMapping("/api/admin/presence")
public class PresenceController {

    private final PresenceTracker tracker;

    public PresenceController(PresenceTracker tracker) {
        this.tracker = tracker;
    }

    @GetMapping
    public PresenceSnapshot online() {
        return tracker.snapshot();
    }
}
