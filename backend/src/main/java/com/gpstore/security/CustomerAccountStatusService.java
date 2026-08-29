package com.gpstore.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.gpstore.entity.Customer;
import com.gpstore.entity.Role;
import com.gpstore.repository.CustomerRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;

/**
 * Live account-status lookup for the JWT filter, with a short cache so a
 * deactivated (or demoted) customer cannot keep using an already-issued
 * access token.
 *
 * Refresh-token revocation (see CustomerService.setAccountActive) only stops
 * the NEXT login. Access JWTs live for minutes after that. This service is
 * what closes that window: every authenticated request re-checks {@code active},
 * {@code enabled}, and {@code role} against the database, or against a cache
 * that expires in {@link #TTL_MS} so a ban or demotion cannot linger.
 *
 * TWO SECONDS, not minutes. A cache that outlives the access token would
 * make this check pointless. Two seconds is long enough to collapse a burst
 * of requests from one screen into a single query, and short enough that a
 * just-banned account is refused on the next tap.
 *
 * Invalidation on deactivate/reactivate/role change is belt-and-suspenders:
 * without it the worst case is a two-second delay, which is already the
 * documented bound. With it, the next request sees the new status immediately.
 */
@Service
public class CustomerAccountStatusService {

    static final long TTL_MS = 2_000L;
    static final long DEFAULT_MAX_ENTRIES = 50_000L;

    public record Snapshot(boolean usable, String role) {
        public static Snapshot unusable() {
            return new Snapshot(false, null);
        }
    }

    private record Cached(boolean usable, String role) {}

    private final CustomerRepository customerRepository;
    private final Cache<Long, Cached> cache;

    @org.springframework.beans.factory.annotation.Autowired
    public CustomerAccountStatusService(CustomerRepository customerRepository) {
        this(customerRepository, DEFAULT_MAX_ENTRIES);
    }

    CustomerAccountStatusService(CustomerRepository customerRepository, long maximumSize) {
        this.customerRepository = customerRepository;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMillis(TTL_MS))
                .maximumSize(maximumSize)
                .executor(Runnable::run)
                .build();
    }

    /**
     * {@code true} only when the account exists, is active, and is enabled.
     * A missing row is treated as unusable: a token for a deleted customer
     * must not authenticate.
     */
    public boolean isUsable(Long customerId) {
        return resolve(customerId).usable();
    }

    /**
     * Live usable flag and role for this account. Missing rows are unusable
     * with a null role.
     */
    public Snapshot resolve(Long customerId) {
        if (customerId == null) {
            return Snapshot.unusable();
        }
        Cached cached = cache.getIfPresent(customerId);
        if (cached != null) {
            return new Snapshot(cached.usable, cached.role);
        }
        Customer customer = customerRepository.findById(customerId).orElse(null);
        boolean usable = isCustomerUsable(customer);
        String role = roleName(customer);
        cache.put(customerId, new Cached(usable, role));
        return new Snapshot(usable, role);
    }

    public void invalidate(Long customerId) {
        if (customerId != null) {
            cache.invalidate(customerId);
        }
    }

    void evictForTests() {
        cache.cleanUp();
    }

    long estimatedSizeForTests() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

    /**
     * JWT role vs live role. A demoted delivery partner (or an escalated
     * token) must not keep the authorities minted at login. Null and blank
     * are treated as CUSTOMER so legacy rows without a role still match a
     * normal customer JWT.
     */
    public static boolean roleMatches(String jwtRole, String liveRole) {
        return Objects.equals(normalizeRole(jwtRole), normalizeRole(liveRole));
    }

    public static boolean isCustomerUsable(Customer customer) {
        if (customer == null) {
            return false;
        }
        // Fail closed on a missing active flag: an account that was never
        // marked active must not inherit a live session from a JWT issued
        // before that column existed.
        if (!Boolean.TRUE.equals(customer.getActive())) {
            return false;
        }
        // enabled=null is treated as enabled (legacy rows). enabled=false
        // is a deleted/disabled account and must not authenticate.
        return !Boolean.FALSE.equals(customer.getEnabled());
    }

    private static String roleName(Customer customer) {
        if (customer == null || customer.getRole() == null) {
            return Role.CUSTOMER.name();
        }
        return customer.getRole().name();
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return Role.CUSTOMER.name();
        }
        return role;
    }
}
