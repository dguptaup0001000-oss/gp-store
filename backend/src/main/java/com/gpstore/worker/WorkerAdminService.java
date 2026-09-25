package com.gpstore.worker;

import com.gpstore.entity.DeliveryPartner;
import com.gpstore.exception.BadRequestException;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.DeliveryPartnerRepository;
import com.gpstore.repository.DeliveryRepository;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.ShopRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The shop's side of a worker: hire, edit, pause, remove.
 *
 * WHAT IS MANDATORY AND WHAT IS NOT. The login email and a password are
 * required, because a worker record that cannot sign in is the thing that
 * wasted an afternoon. Everything else - phone, vehicle, registration - is
 * detail the shop fills in when it has it, and a missing vehicle number must
 * never be the reason a rider cannot start work.
 *
 * NOTHING HERE TOUCHES THE CUSTOMERS TABLE. Creating a worker writes one row.
 * That is what makes the owner's own address usable as a worker login: it is
 * a different credential in a different table, not a password being set on
 * their administrator account.
 */
@Service
public class WorkerAdminService {

    /** Long enough to be worth having, short enough to read down a phone line. */
    static final int MIN_PASSWORD_LENGTH = 8;

    /** A pause is temporary by definition; anything longer is "switch them off". */
    static final long MAX_SUSPENSION_MINUTES = Duration.ofDays(30).toMinutes();

    private final DeliveryPartnerRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final WorkerAccessService accessService;
    private final DeliveryRepository deliveries;
    private final ShopRepository shops;

    public WorkerAdminService(DeliveryPartnerRepository repository,
                              PasswordEncoder passwordEncoder,
                              WorkerAccessService accessService,
                              DeliveryRepository deliveries,
                              ShopRepository shops) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.accessService = accessService;
        this.deliveries = deliveries;
        this.shops = shops;
    }

    /** Everything the shop typed, plus what the server derived. Never the hash. */
    public record WorkerView(
            Long id,
            String name,
            String mobile,
            String loginEmail,
            String vehicleType,
            String vehicleNumber,
            boolean available,
            boolean active,
            boolean canSignIn,
            boolean suspended,
            LocalDateTime suspendedUntil,
            String suspensionReason) {
    }

    /** The write shape. A blank password on an update means "leave it alone". */
    public record WorkerForm(
            String name,
            String mobile,
            String loginEmail,
            String password,
            String vehicleType,
            String vehicleNumber,
            Boolean available) {
    }

    public record DeliverySummary(Long deliveryId, String orderNumber, String status,
                                  LocalDateTime assignedAt, LocalDateTime deliveredAt) { }

    public record WorkerProfile(WorkerView worker, Long shopId, String shopName,
                                long totalAssigned, long completed,
                                long active, long exceptions, int page, int size,
                                boolean hasNext, List<DeliverySummary> currentWork,
                                List<DeliverySummary> history) { }

    /** One bounded, tenant-checked Worker 360 response; history is paginated. */
    @Transactional(readOnly = true)
    public WorkerProfile profile(Long id, int page, int size) {
        if (page < 0 || size < 1 || size > 50) {
            throw new BadRequestException("History page must be non-negative and size must be 1–50.");
        }
        DeliveryPartner worker = live(id);
        var scope = TenantContext.require();
        if (scope.isSingleShop() && !scope.shopId().equals(worker.getShopId())) {
            throw new AccessDeniedException("This worker is outside the selected shop.");
        }

        List<Object[]> raw = deliveries.workerHistory(id, PageRequest.of(page, size + 1));
        boolean hasNext = raw.size() > size;
        List<DeliverySummary> history = raw.stream().limit(size)
                .map(WorkerAdminService::deliverySummary).toList();
        List<DeliverySummary> current = deliveries.workerCurrentWork(id, PageRequest.of(0, 50))
                .stream().map(WorkerAdminService::deliverySummary).toList();
        long completed = 0, active = 0, exceptions = 0, total = 0;
        for (Object[] row : deliveries.workerDeliveryCounts(id)) {
            String status = String.valueOf(row[0]);
            long count = ((Number) row[1]).longValue();
            total += count;
            if ("DELIVERED".equals(status)) completed += count;
            else if ("CANCELLED".equals(status)) exceptions += count;
            else active += count;
        }
        String shopName = worker.getShopId() == null ? null
                : shops.findById(worker.getShopId()).map(shop -> shop.getDisplayName()).orElse(null);
        return new WorkerProfile(describe(worker), worker.getShopId(), shopName, total, completed, active, exceptions,
                page, size, hasNext, current, history);
    }

    private static DeliverySummary deliverySummary(Object[] row) {
        return new DeliverySummary((Long) row[0], (String) row[1], (String) row[2],
                (LocalDateTime) row[3], (LocalDateTime) row[4]);
    }

    @Transactional(readOnly = true)
    public List<WorkerView> list() {
        return repository.findByDeletedAtIsNull(Sort.by(Sort.Direction.ASC, "name"))
                .stream().map(WorkerAdminService::describe).toList();
    }

    @Transactional(readOnly = true)
    public WorkerView get(Long id) {
        return describe(live(id));
    }

    @Transactional
    public WorkerView create(WorkerForm form) {
        DeliveryPartner worker = new DeliveryPartner();
        worker.setActive(true);
        applyDetails(worker, form, null);

        // Required on create and nowhere else: an existing worker being edited
        // already has one, and forcing it to be retyped is how it gets changed
        // by accident.
        String password = form.password() == null ? "" : form.password().trim();
        requireUsablePassword(password);
        worker.setPasswordHash(passwordEncoder.encode(password));

        return describe(repository.save(worker));
    }

    @Transactional
    public WorkerView update(Long id, WorkerForm form) {
        DeliveryPartner worker = live(id);
        applyDetails(worker, form, worker.getId());

        String password = form.password() == null ? "" : form.password().trim();
        if (!password.isEmpty()) {
            requireUsablePassword(password);
            worker.setPasswordHash(passwordEncoder.encode(password));
        }

        DeliveryPartner saved = repository.save(worker);
        accessService.invalidate(saved.getId());
        return describe(saved);
    }

    /**
     * Closed for a while, and it reopens on its own.
     *
     * The caller sends minutes, so "an hour" and "a day" are the same call
     * with different numbers and the server keeps one rule instead of three.
     */
    @Transactional
    public WorkerView suspend(Long id, long minutes, String reason) {
        if (minutes <= 0) {
            throw new BadRequestException("Choose how long to pause this worker for.");
        }
        if (minutes > MAX_SUSPENSION_MINUTES) {
            throw new BadRequestException(
                    "A pause can be at most 30 days. To stop someone for longer, "
                            + "switch the worker off or remove them.");
        }
        DeliveryPartner worker = live(id);
        worker.setSuspendedUntil(LocalDateTime.now().plusMinutes(minutes));
        worker.setSuspensionReason(reason == null || reason.isBlank() ? null : reason.trim());
        DeliveryPartner saved = repository.save(worker);
        // So the bar lands on their very next request rather than up to the
        // status cache's TTL later.
        accessService.invalidate(saved.getId());
        return describe(saved);
    }

    /** Ends a pause early. Safe to call on a worker who is not paused. */
    @Transactional
    public WorkerView resume(Long id) {
        DeliveryPartner worker = live(id);
        worker.setSuspendedUntil(null);
        worker.setSuspensionReason(null);
        DeliveryPartner saved = repository.save(worker);
        accessService.invalidate(saved.getId());
        return describe(saved);
    }

    /**
     * Removes the worker from the roster and from the login screen.
     *
     * SOFT, and that is a decision rather than a shortcut. Deliveries point at
     * this row, so erasing it would leave finished orders with no rider and
     * make the shop's own history wrong. Clearing the login as well means the
     * address and phone number are immediately free to give to somebody else,
     * which is what a shop expects after a rider leaves.
     */
    @Transactional
    public void delete(Long id) {
        DeliveryPartner worker = live(id);
        worker.setDeletedAt(LocalDateTime.now());
        worker.setAvailable(false);
        worker.setActive(false);
        repository.save(worker);
        accessService.invalidate(worker.getId());
    }

    // ------------------------------------------------------------- internals

    private DeliveryPartner live(Long id) {
        DeliveryPartner worker = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Worker not found"));
        if (worker.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Worker not found");
        }
        return worker;
    }

    private void applyDetails(DeliveryPartner worker, WorkerForm form, Long selfId) {
        String name = trimmed(form.name());
        if (name == null) {
            throw new BadRequestException("A name is required.");
        }
        String email = trimmed(form.loginEmail());
        if (email == null) {
            throw new BadRequestException("A login email is required - it is how they sign in.");
        }
        if (!email.contains("@") || email.startsWith("@") || email.endsWith("@")) {
            throw new BadRequestException("That does not look like an email address.");
        }
        String mobile = form.mobile() == null ? null
                : blankToNull(WorkerAuthService.normaliseMobile(form.mobile()));
        if (mobile != null && mobile.length() != 10) {
            throw new BadRequestException("A phone number must be 10 digits.");
        }

        // Both identifiers are accepted at the login screen, so a duplicate of
        // either has no correct answer. Checked here as well as by the unique
        // index so the shop gets a sentence instead of a constraint violation.
        repository.findByLoginEmailIgnoreCaseAndDeletedAtIsNull(email)
                .filter(other -> !other.getId().equals(selfId))
                .ifPresent(other -> {
                    throw new ConflictException(
                            "That login email already belongs to " + other.getName() + ".");
                });
        if (mobile != null) {
            String finalMobile = mobile;
            repository.findByMobileAndDeletedAtIsNull(mobile)
                    .filter(other -> !other.getId().equals(selfId))
                    .ifPresent(other -> {
                        throw new ConflictException(
                                "Phone number " + finalMobile + " already belongs to "
                                        + other.getName() + ".");
                    });
        }

        worker.setName(name);
        worker.setLoginEmail(email);
        worker.setMobile(mobile);
        worker.setVehicleType(blankToNull(form.vehicleType()));
        worker.setVehicleNumber(blankToNull(form.vehicleNumber()));
        worker.setAvailable(form.available() == null ? Boolean.TRUE : form.available());
        if (worker.getActive() == null) {
            worker.setActive(true);
        }
    }

    private static void requireUsablePassword(String password) {
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new BadRequestException(
                    "The password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
    }

    private static String trimmed(String value) {
        return blankToNull(value == null ? null : value.trim());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static WorkerView describe(DeliveryPartner worker) {
        WorkerAccess.Decision decision = WorkerAccess.check(worker, LocalDateTime.now());
        boolean suspended = decision.verdict() == WorkerAccess.Verdict.SUSPENDED;
        return new WorkerView(
                worker.getId(),
                worker.getName(),
                worker.getMobile(),
                worker.getLoginEmail(),
                worker.getVehicleType(),
                worker.getVehicleNumber(),
                Boolean.TRUE.equals(worker.getAvailable()),
                Boolean.TRUE.equals(worker.getActive()),
                decision.allowed(),
                suspended,
                // Only reported while it is still in force, so a stale
                // timestamp from last week cannot render as a live pause.
                suspended ? worker.getSuspendedUntil() : null,
                suspended ? worker.getSuspensionReason() : null);
    }
}
