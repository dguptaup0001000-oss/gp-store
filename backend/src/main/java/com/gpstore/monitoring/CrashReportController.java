package com.gpstore.monitoring;

import com.gpstore.dto.request.CrashReportRequest;
import com.gpstore.security.AuthenticatedUser;
import com.gpstore.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Where a phone posts the crash it just survived.
 *
 * ONE ENDPOINT, WRITE ONLY. Reading these back is deliberately not here: a
 * crash list is staff data, it is read from the admin surface under the
 * permissions that already govern staff reads, and an app that can write its
 * own crashes has no business enumerating everybody else's.
 */
@RestController
@RequestMapping("/api/client/crash-reports")
public class CrashReportController {

    private final CrashReportService crashReportService;
    private final CurrentUser currentUser;

    public CrashReportController(CrashReportService crashReportService, CurrentUser currentUser) {
        this.crashReportService = crashReportService;
        this.currentUser = currentUser;
    }

    /**
     * ALWAYS 202, whether the row was stored or dropped.
     *
     * The caller is an app in the middle of dying. A 4xx here would hand it a
     * second failure to handle at the one moment it is least able to, and
     * there is no action it could take in response - it cannot make its own
     * crash less frequent or its stack shorter. Accepted-and-maybe-dropped is
     * the honest contract, and the reasons a report is dropped (hourly cap,
     * blank message) are all cases where the client is not the one at fault.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void report(@Valid @RequestBody CrashReportRequest request) {
        AuthenticatedUser user = currentUser.get();
        // FROM THE TOKEN, NOT THE BODY. CrashReportRequest has no field for
        // either of these, so there is nothing here to override.
        crashReportService.record(user.getCustomerId(), user.getWorkerId(), request);
    }
}
