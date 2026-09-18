package com.gpstore.repository;

import com.gpstore.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository
        extends JpaRepository<Notification, Long> {

    /**
     * Notifications about THIS shop's orders, newest first.
     *
     * <p>WAS findAll(). The admin notification log returned every notification
     * ever sent to anyone on the platform, each carrying the order number and
     * status it was about - so one merchant could read the order flow of every
     * other merchant. No app calls it today, which is why nobody noticed.
     *
     * <p>Order IS a {@code ShopOwned} entity, so making it the root of an EXISTS
     * subquery is what applies the tenant filter and narrows the log to this
     * shop without the query naming a shop.
     *
     * <p>THE SUBQUERY IS LOad-BEARING, and the first version of this got it
     * wrong. It read {@code WHERE n.order IS NOT NULL}, which mentions the
     * association but never makes Order a query root - so Hibernate's filter
     * had nothing to attach to and the query returned notifications about every
     * shop's orders. It LOOKED correct because the rows then blew up on load:
     * TenantEntityListener's @PostLoad check saw a foreign shop_id and threw,
     * which surfaced as a 404 rather than a leak. A guard catching a query that
     * should never have asked is not the query being right.
     *
     * <p>BROADCASTS ARE EXCLUDED FOR A SHOP, deliberately. A notification with
     * no order is a platform announcement, not this shop's traffic; showing
     * them here would put GP-STORE's own messages in a merchant's operational
     * log and, worse, put other shops' announcements there too.
     */
    @Query("SELECT n FROM Notification n WHERE EXISTS ("
            + "  SELECT 1 FROM Order o WHERE o = n.order) "
            + "ORDER BY n.sentAt DESC, n.id DESC")
    Page<Notification> findAllForCurrentShop(Pageable pageable);


    List<Notification> findByCustomerId(Long customerId);

    List<Notification> findByCustomerIdOrderBySentAtDesc(Long customerId);

    /**
     * The customer feed, with each notification's order fetched in the same
     * query.
     *
     * NotificationResponse links back to an order, and Notification.order is
     * a LAZY @ManyToOne - so mapping a page of 20 issued 1 query for the
     * page plus up to 20 more for the orders. LEFT (not inner) join because
     * broadcasts legitimately have no order and must still appear.
     */
    @Query(value = "select n from Notification n left join fetch n.order "
            + "where n.customer.id = :customerId order by n.sentAt desc",
            countQuery = "select count(n) from Notification n where n.customer.id = :customerId")
    Page<Notification> findByCustomerIdOrderBySentAtDesc(@Param("customerId") Long customerId, Pageable pageable);

    /**
     * Paginated deliberately. A single order accumulates a notification per
     * status change, plus any resend, so this grows with an order's history
     * rather than being naturally bounded - and nothing about the old
     * List-returning signature stopped a caller loading all of them.
     */
    Page<Notification> findByOrderId(Long orderId, Pageable pageable);

    long countByCustomerIdAndIsReadFalse(Long customerId);

    /**
     * Bulk delete for account deletion. The previous
     * deleteAll(findByCustomerId(...)) loaded every notification the account
     * had ever received into memory and issued one DELETE per row - for a
     * long-lived account that is a large load and a long transaction, on a
     * path the user is waiting on.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Notification n where n.customer.id = :customerId")
    int deleteByCustomerId(@Param("customerId") Long customerId);

    /**
     * Marks every unread notification for one customer as read in a single
     * statement.
     *
     * Replaces a load-all-then-loop-then-saveAll implementation, which
     * pulled every notification a customer had ever received into JVM
     * memory, dirtied each one, and issued an UPDATE per row - unbounded in
     * both memory and query count, and growing for the life of the account.
     *
     * The `and isRead = false` predicate is not just an optimisation: it
     * keeps the statement's write set to rows that actually change, so
     * repeated calls (a double-tapped "Mark all read") touch nothing the
     * second time instead of rewriting the customer's whole history.
     *
     * clearAutomatically/flushAutomatically keep the persistence context
     * honest - without them, entities already loaded in this transaction
     * would still report their stale isRead value after this runs.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.isRead = true "
            + "where n.customer.id = :customerId and n.isRead = false")
    int markAllAsReadForCustomer(@Param("customerId") Long customerId);
}