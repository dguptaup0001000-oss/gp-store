package com.gpstore.repository;

import com.gpstore.entity.CustomerAppSession;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface CustomerAppSessionRepository extends JpaRepository<CustomerAppSession, Long> {

    /**
     * Total seconds this customer has spent in the app.
     *
     * COALESCE because a customer who has never opened the app has no rows,
     * and a null total would surface as "unknown" on a screen where the honest
     * answer is "none yet". Those are different statements.
     */
    @Query("select coalesce(sum(s.seconds), 0) from CustomerAppSession s "
            + "where s.customer.id = :customerId")
    long totalSecondsFor(@Param("customerId") Long customerId);

    @Query("select count(s) from CustomerAppSession s where s.customer.id = :customerId")
    long sessionCountFor(@Param("customerId") Long customerId);

    /** When they were last in the app at all. Null if never. */
    @Query("select max(s.endedAt) from CustomerAppSession s where s.customer.id = :customerId")
    LocalDateTime lastSeenFor(@Param("customerId") Long customerId);

    /**
     * The most recent sessions, newest first.
     *
     * Pageable rather than a List of everything: a customer who has used the
     * app daily for a year has hundreds of rows, and an admin screen needs the
     * last handful, not the archive.
     */
    @Query("select s from CustomerAppSession s where s.customer.id = :customerId "
            + "order by s.startedAt desc")
    List<CustomerAppSession> recentFor(@Param("customerId") Long customerId, Pageable pageable);

    /**
     * Guards the write path against a client that reports constantly.
     *
     * A phone backgrounding and foregrounding in a loop - or an app with a
     * bug - would otherwise write a row every second and turn a usage figure
     * into a flood. Counted per hour rather than rate-limited by IP because
     * the thing worth bounding is rows per CUSTOMER.
     */
    @Query("select count(s) from CustomerAppSession s "
            + "where s.customer.id = :customerId and s.createdAt >= :since")
    long countRecordedSince(@Param("customerId") Long customerId,
                            @Param("since") LocalDateTime since);

    /**
     * Account deletion takes the usage history with it.
     *
     * NOT OPTIONAL, AND NOT COSMETIC. Deleting an account anonymises the
     * customer row in place rather than removing it, so without this the
     * seconds a real person spent in the app would outlive the account that
     * asked to be deleted, still joined to the same id. Play's account
     * deletion requirement and docs/PLAY_STORE_DECLARATIONS.md sec. 8 both
     * say this data goes; this is the line that makes that true.
     *
     * Bulk delete rather than loading the rows first - a daily user has
     * hundreds, and they load into memory inside the transaction the person
     * is waiting on. Same shape as the wishlist and address deletes beside it.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from CustomerAppSession s where s.customer.id = :customerId")
    int deleteByCustomerIdBulk(@Param("customerId") Long customerId);
}
