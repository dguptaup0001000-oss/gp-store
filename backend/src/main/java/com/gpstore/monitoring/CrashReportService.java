package com.gpstore.monitoring;

import com.gpstore.dto.request.CrashReportRequest;
import com.gpstore.entity.ClientCrashReport;
import com.gpstore.repository.ClientCrashReportRepository;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.repository.DeliveryPartnerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Stores a crash a phone reported about itself, without believing the phone.
 *
 * Same posture as AppSessionService, for the same reason: everything in the
 * body is a claim. What separates a useful diagnostic from a liability is
 * which parts the server refuses to take from the client.
 *
 *   - WHO is never read from the body. It comes from the authenticated
 *     principal, so no signed-in account can file crashes against another.
 *   - WHICH APP is derived from that same principal, not from a field, so a
 *     customer login cannot fill the worker app's crash list with noise.
 *   - HOW MUCH is capped. The stack is truncated rather than rejected,
 *     because the first thousand characters of a trace is where the answer
 *     usually is, and refusing an over-long body would mean the worst
 *     crashes - the ones with the deepest stacks - are exactly the ones never
 *     recorded.
 *   - HOW OFTEN is capped per reporter. A crash loop is the normal case, not
 *     the abusive one: an app that dies on startup dies again on restart. The
 *     rate limiter bounds requests; this bounds rows, so twenty identical
 *     reports cannot bury yesterday's interesting one.
 */
@Service
public class CrashReportService {

    private static final Logger log = LoggerFactory.getLogger(CrashReportService.class);

    private final ClientCrashReportRepository repository;
    private final CustomerRepository customerRepository;
    private final DeliveryPartnerRepository partnerRepository;
    private final int maxStackChars;
    private final int maxMessageChars;
    private final int maxPerReporterPerHour;
    private final int retentionDays;

    public CrashReportService(
            ClientCrashReportRepository repository,
            CustomerRepository customerRepository,
            DeliveryPartnerRepository partnerRepository,
            // Enough for a deep Flutter trace and its "caused by" tail. Past
            // this the frames are framework plumbing that repeats in every
            // report.
            @Value("${crash.max-stack-chars:8000}") int maxStackChars,
            @Value("${crash.max-message-chars:500}") int maxMessageChars,
            // A person restarting a dying app a few times an hour is normal.
            // Thirty is well past that and still far below "fills the disk".
            @Value("${crash.max-per-reporter-per-hour:30}") int maxPerReporterPerHour,
            @Value("${crash.retention-days:30}") int retentionDays) {
        this.repository = repository;
        this.customerRepository = customerRepository;
        this.partnerRepository = partnerRepository;
        this.maxStackChars = maxStackChars;
        this.maxMessageChars = maxMessageChars;
        this.maxPerReporterPerHour = maxPerReporterPerHour;
        this.retentionDays = retentionDays;
    }

    /**
     * Returns false when the report was dropped rather than stored.
     *
     * DROPPED IS NOT AN ERROR, and the controller answers 202 either way.
     * The caller is an app that has just crashed; telling it that its crash
     * report also failed gives it a second error to handle at the worst
     * possible moment, and there is nothing useful it could do with the news.
     */
    @Transactional
    public boolean record(Long customerId, Long workerId, CrashReportRequest request) {

        if (customerId == null && workerId == null) {
            // Belt and braces - SecurityConfig requires authentication, so
            // this is unreachable through HTTP. It exists so a future caller
            // that skips the filter cannot write an unattributed row.
            return false;
        }

        String message = trim(request.getMessage(), maxMessageChars);
        if (message == null || message.isBlank()) {
            return false;
        }

        long already = repository.countRecentFrom(
                LocalDateTime.now().minusHours(1), customerId, workerId);
        if (already >= maxPerReporterPerHour) {
            // Deliberately not a warning per occurrence: a crash loop would
            // then move the flood from the table into the log file.
            log.debug("Crash report dropped - reporter already at the hourly cap");
            return false;
        }

        ClientCrashReport report = new ClientCrashReport();
        report.setApp(workerId != null ? ClientCrashReport.App.WORKER : ClientCrashReport.App.CUSTOMER);
        report.setMessage(message);
        report.setStack(trim(request.getStack(), maxStackChars));
        report.setAppVersion(trim(request.getAppVersion(), 32));
        report.setBuildSha(trim(request.getBuildSha(), 40));
        report.setPlatform(trim(request.getPlatform(), 32));
        report.setFatal(request.getFatal() == null || request.getFatal());
        report.setReportedAt(LocalDateTime.now());

        if (workerId != null) {
            partnerRepository.findById(workerId).ifPresent(report::setWorker);
        }
        if (customerId != null) {
            customerRepository.findById(customerId).ifPresent(report::setCustomer);
        }

        repository.save(report);

        // ONE LINE, AND NOT THE STACK. The row is where the detail lives; the
        // log line exists so somebody tailing the backend sees that a rider's
        // app is dying without a stack trace from a phone being pasted into
        // the shop's server log every few seconds.
        log.info("Client crash recorded: app={} fatal={} version={}",
                report.getApp(), report.getFatal(), report.getAppVersion());
        return true;
    }

    /**
     * Crash reports are diagnostics, not records the shop has to keep.
     *
     * WITHOUT THIS the table only grows. The volume is small - a handful of
     * rows on a bad day - so this is one bounded statement rather than the
     * batched sweep IdempotencyRetentionService needs for a table that live
     * checkout inserts into. @SchedulerLock for the same reason as every
     * other sweep here: once there is a second instance, only one should run.
     *
     * A month is long enough to answer "has this been happening since the
     * last release" and short enough that nobody has to think about it.
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${crash.cleanup-interval-ms:86400000}",
            initialDelayString = "${crash.cleanup-initial-delay-ms:300000}")
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(
            name = "deleteOldClientCrashReports",
            lockAtMostFor = "10m",
            lockAtLeastFor = "1m")
    @Transactional
    public void deleteOldReports() {
        int deleted = repository.deleteReportedBefore(
                LocalDateTime.now().minusDays(retentionDays));
        if (deleted > 0) {
            log.info("Deleted {} crash report(s) older than {} days", deleted, retentionDays);
        }
    }

    private String trim(String value, int max) {
        if (value == null) return null;
        String stripped = value.strip();
        if (stripped.isEmpty()) return null;
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }
}
