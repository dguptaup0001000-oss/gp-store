package com.gpstore.repository;

import com.gpstore.entity.ClientCrashReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface ClientCrashReportRepository extends JpaRepository<ClientCrashReport, Long> {

    /** Newest first - the only order anyone reads these in. */
    Page<ClientCrashReport> findAllByOrderByReportedAtDesc(Pageable pageable);

    Page<ClientCrashReport> findByAppOrderByReportedAtDesc(ClientCrashReport.App app, Pageable pageable);

    /**
     * How many this reporter has already filed in the window.
     *
     * A CRASH LOOP IS THE NORMAL CASE, not the abusive one: an app that dies
     * on startup restarts and dies again, and a rider will happily tap the
     * icon twenty times. The rate limiter bounds the request rate; this
     * bounds what actually gets stored, so twenty identical rows do not push
     * out the one interesting crash from yesterday.
     */
    @Query("""
            select count(r) from ClientCrashReport r
            where r.reportedAt >= :since
              and (
                    (:customerId is not null and r.customer.id = :customerId)
                 or (:workerId   is not null and r.worker.id   = :workerId)
              )
            """)
    long countRecentFrom(@Param("since") LocalDateTime since,
                         @Param("customerId") Long customerId,
                         @Param("workerId") Long workerId);

    /** Retention: these are diagnostics, not records the shop must keep. */
    @Query("delete from ClientCrashReport r where r.reportedAt < :before")
    @org.springframework.data.jpa.repository.Modifying
    int deleteReportedBefore(@Param("before") LocalDateTime before);
}
