package com.gpstore.worker;

import com.gpstore.entity.DeliveryPartner;
import com.gpstore.exception.AuthException;
import com.gpstore.repository.DeliveryPartnerRepository;
import com.gpstore.service.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Signing in to the worker app.
 *
 * ONE IDENTIFIER FIELD, because a rider standing in the street should not
 * have to remember which of their two identifiers the shop typed in. Phone
 * number or email address, whichever they have to hand, both resolve to the
 * same roster row.
 *
 * IT READS NOTHING BUT delivery_partners. No customer lookup, no role, no
 * account link. That is the whole point of the redesign: the previous version
 * authenticated workers through the customers table, so the owner's own Gmail
 * - a staff account - could not be given a worker password without that being
 * a privilege escalation, and the shop was left unable to put itself on its
 * own roster.
 */
@Service
public class WorkerAuthService {

    private static final Logger log = LoggerFactory.getLogger(WorkerAuthService.class);

    /**
     * Deliberately identical for "no such worker" and "wrong password".
     *
     * Two different sentences turn this endpoint into a way to ask whether a
     * given phone number works at the shop.
     */
    private static final String REFUSED =
            "Wrong login details. Check them with the shop.";

    private final DeliveryPartnerRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final WorkerAccessService accessService;
    private final long sessionMs;

    public WorkerAuthService(
            DeliveryPartnerRepository repository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            WorkerAccessService accessService,
            // ONE SHIFT, not one hour. A rider on a bike cannot stop to sign
            // in again mid-round, and there is no refresh token to do it for
            // them. That costs nothing in revocation: JwtFilter re-checks the
            // roster row on every request, so removing or pausing a worker
            // still takes effect on their next tap.
            @Value("${worker.session-ms:43200000}") long sessionMs) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.accessService = accessService;
        this.sessionMs = sessionMs;
    }

    public record Session(String accessToken, long workerId, String name, String loginEmail, String mobile) {
    }

    @Transactional(readOnly = true)
    public Session login(String rawIdentifier, String rawPassword) {
        String identifier = rawIdentifier == null ? "" : rawIdentifier.trim();
        String password = rawPassword == null ? "" : rawPassword;

        if (identifier.isEmpty() || password.isEmpty()) {
            throw new AuthException(REFUSED);
        }

        DeliveryPartner worker = findByIdentifier(identifier).orElse(null);

        // ALWAYS RUN THE HASH, even with no worker to check it against.
        // Returning early here makes an unknown identifier measurably faster
        // than a known one with a wrong password, which hands out exactly the
        // answer the shared message above is hiding.
        String storedHash = worker == null ? null : worker.getPasswordHash();
        boolean passwordMatches = verify(password, storedHash);

        if (worker == null || !passwordMatches) {
            // Never the identifier, never the password - one is the shop's
            // customer data and the other is a credential.
            log.info("Worker sign-in refused: bad credentials");
            throw new AuthException(REFUSED);
        }

        // Deleted, switched off, suspended, or never given a login. Checked
        // AFTER the password so the state of an account is only ever revealed
        // to whoever already knows its password.
        WorkerAccess.Decision decision = accessService.check(worker);
        if (!decision.allowed()) {
            log.info("Worker sign-in refused for workerId={}: {}", worker.getId(), decision.verdict());
            throw new AuthException(decision.message());
        }

        return new Session(
                jwtService.generateWorkerToken(worker.getId(), worker.getLoginEmail(), sessionMs),
                worker.getId(),
                worker.getName(),
                worker.getLoginEmail(),
                worker.getMobile());
    }

    /**
     * Email if it looks like one, phone otherwise.
     *
     * The '@' test is enough and intentionally crude: an identifier with an @
     * in it is never a phone number, and one without is never an email
     * address. Digits are compared after stripping spaces, dashes and a
     * country code, because a rider types their number the way they say it
     * and the shop typed it the way it was written down.
     */
    private Optional<DeliveryPartner> findByIdentifier(String identifier) {
        if (identifier.contains("@")) {
            return repository.findByLoginEmailIgnoreCaseAndDeletedAtIsNull(identifier);
        }
        String digits = normaliseMobile(identifier);
        if (digits.isEmpty()) {
            return Optional.empty();
        }
        return repository.findByMobileAndDeletedAtIsNull(digits);
    }

    /** Digits only, and the last ten of them - so +91 63882 93365 finds 6388293365. */
    static String normaliseMobile(String raw) {
        String digits = raw.replaceAll("\\D", "");
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }

    private boolean verify(String password, String storedHash) {
        if (storedHash == null || storedHash.isBlank()) {
            // Match against a hash that cannot succeed rather than skipping
            // the work, so a worker with no password set costs the same time
            // as one with the wrong password.
            passwordEncoder.matches(password, "$2a$10$ThisIsNotARealHashItOnlyBurnsTheSameTimeXXXXXXXXXXXXX");
            return false;
        }
        return passwordEncoder.matches(password, storedHash);
    }
}
