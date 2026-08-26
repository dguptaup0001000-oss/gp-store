package com.gpstore.security;

import com.gpstore.entity.Customer;
import com.gpstore.repository.CustomerRepository;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Live account-status lookup for the JWT filter, with a short cache so a
 * deactivated customer cannot keep using an already-issued access token.
 *
 * Refresh-token revocation (see CustomerService.setAccountActive) only stops
 * the NEXT login. Access JWTs live for minutes after that. This service is
 * what closes that window: every authenticated request re-checks {@code active}
 * (and {@code enabled}) against the database, or against a cache that expires
 * in {@link #TTL_MS} so a ban cannot linger.
 *
 * TWO SECONDS, not minutes. A cache that outlives the access token would
 * make this check pointless. Two seconds is long enough to collapse a burst
 * of requests from one screen into a single query, and short enough that a
 * just-banned account is refused on the next tap.
 *
 * Invalidation on deactivate/reactivate is belt-and-suspenders: without it
 * the worst case is a two-second delay, which is already the documented
 * bound. With it, the next request sees the new status immediately.
 */
@Service
public class CustomerAccountStatusService {

    static final long TTL_MS = 2_000L;

    private record Cached(boolean usable, long expiresAtMs) {}

    private final CustomerRepository customerRepository;
    private final ConcurrentHashMap<Long, Cached> cache = new ConcurrentHashMap<>();

    public CustomerAccountStatusService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    /**
     * {@code true} only when the account exists, is active, and is enabled.
     * A missing row is treated as unusable: a token for a deleted customer
     * must not authenticate.
     */
    public boolean isUsable(Long customerId) {
        if (customerId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Cached cached = cache.get(customerId);
        if (cached != null && cached.expiresAtMs > now) {
            return cached.usable;
        }
        boolean usable = customerRepository.findById(customerId)
                .map(CustomerAccountStatusService::isCustomerUsable)
                .orElse(false);
        cache.put(customerId, new Cached(usable, now + TTL_MS));
        return usable;
    }

    public void invalidate(Long customerId) {
        if (customerId != null) {
            cache.remove(customerId);
        }
    }

    static boolean isCustomerUsable(Customer customer) {
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
}
