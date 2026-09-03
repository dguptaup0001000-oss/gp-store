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


    /**
     * Bulk delete for account deletion, but ONLY the addresses nothing needs.
     *
     * orders.address_id is a foreign key to this table with NO ACTION on
     * delete, so deleting an address an order still points at is refused by
     * the database - which used to fail the whole account deletion for any
     * customer who had ever bought anything. The referenced ones are scrubbed
     * instead; see CustomerService.deleteOwnAccount.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            delete from Address a
            where a.customer.id = :customerId
              and not exists (select 1 from Order o where o.address = a)
            """)
    int deleteUnreferencedByCustomerIdBulk(
            @org.springframework.data.repository.query.Param("customerId") Long customerId);

    /**
     * Takes the person out of an address the shop's own order history still
     * needs, and detaches it from the account.
     *
     * WHAT SURVIVES AND WHY. The row stays because an order has to keep a
     * record of where it went - that is the shop's accounting, not the
     * customer's data - but everything that identifies a human being goes:
     * the name, the phone, the door, the coordinates, and the directions
     * somebody wrote to their own home.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            update Address a set
                a.customer = null,
                a.fullName = 'Deleted User',
                a.mobileNumber = null,
                a.houseNo = null,
                a.buildingName = null,
                a.floor = null,
                a.landmark = null,
                a.deliveryInstructions = null,
                a.formattedAddress = null,
                a.placeId = null,
                a.latitude = null,
                a.longitude = null,
                a.locationAccuracy = null,
                a.label = null
            where a.customer.id = :customerId
            """)
    int anonymiseByCustomerIdBulk(
            @org.springframework.data.repository.query.Param("customerId") Long customerId);
}