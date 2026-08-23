package com.gpstore.repository;

import java.util.List;

import com.gpstore.entity.Address;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressRepository extends JpaRepository<Address, Long> {

    /**
     * The fetch join on subzone is here so server-side callers can read
     * address.getSubzone() after the transaction has closed.
     *
     * It is NOT what keeps the response serialisable - Address.subzone is
     * @JsonIgnore, so no client ever sees it (see the comment on that field
     * for why a customer's phone should not be receiving a delivery
     * partner's phone number). What it prevents is the other half of the
     * same problem: with open-session-in-view off (see
     * spring.jpa.open-in-view) a lazy read from dispatch or admin code
     * outside a transaction is an exception rather than a silent extra query.
     *
     * A @ManyToOne fetch join is safe to paginate - unlike a collection, it
     * multiplies no rows.
     */
    @org.springframework.data.jpa.repository.Query(
            "select a from Address a left join fetch a.subzone where a.customer.id = :customerId")
    List<Address> findByCustomerId(@org.springframework.data.repository.query.Param("customerId") Long customerId);

    @org.springframework.data.jpa.repository.Query(
            "select a from Address a left join fetch a.subzone where a.id = :id")
    java.util.Optional<Address> findByIdWithSubzone(
            @org.springframework.data.repository.query.Param("id") Long id);

    @org.springframework.data.jpa.repository.Query(
            value = "select a from Address a left join fetch a.subzone",
            countQuery = "select count(a) from Address a")
    org.springframework.data.domain.Page<Address> findAllWithSubzone(
            org.springframework.data.domain.Pageable pageable);


    /** Bulk delete for account deletion - see NotificationRepository.deleteByCustomerId. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from Address a where a.customer.id = :customerId")
    int deleteByCustomerIdBulk(@org.springframework.data.repository.query.Param("customerId") Long customerId);
}