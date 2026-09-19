package com.gpstore.notify;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PushRegistrationRepository extends JpaRepository<PushRegistration, Long> {

    Optional<PushRegistration> findByToken(String token);

    List<PushRegistration> findByCustomerIdAndApp(Long customerId, String app);

    List<PushRegistration> findByCustomerId(Long customerId);

    /**
     * Every enabled install of one app belonging to any of these accounts.
     *
     * <p>ORDINARY JPA, NOT NATIVE, because this table is not shop-owned and the
     * shop filter has nothing to say about it. The shop-scoped half of the
     * question - WHICH accounts - is answered separately and deliberately, in
     * {@link NewOrderAlerts}, from live {@code shop_staff} rows.
     */
    @Query("SELECT r FROM PushRegistration r "
            + "WHERE r.customerId IN :customerIds AND r.app = :app AND r.enabled = true")
    List<PushRegistration> enabledFor(@Param("customerIds") Collection<Long> customerIds,
                                      @Param("app") String app);
}
