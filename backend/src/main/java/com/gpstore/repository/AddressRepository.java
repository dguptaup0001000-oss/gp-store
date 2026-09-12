package com.gpstore.repository;

import java.util.List;

import com.gpstore.entity.Address;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressRepository extends JpaRepository<Address, Long> {

    /**
     * THE SUBZONE IS NO LONGER FETCHED HERE, and removing it fixed an outage
     * rather than saving a join.
     *
     * These three queries used to carry "left join fetch a.subzone" so
     * server-side callers could read address.getSubzone() outside a
     * transaction, with open-session-in-view off. Slice 9 made
     * delivery_subzones shop-owned, and that turned the same fetch into a
     * trap: an address is a CUSTOMER's row and spans every shop they buy
     * from, but addresses.subzone_id holds one value. So a customer whose
     * address was stamped in Shop A's map, listing their addresses while
     * shopping at Shop B, would have had a shop-owned row from Shop A loaded
     * into Shop B's scope - and TenantEntityListener's @PostLoad would refuse
     * it and fail the request. The customer could not read their own address
     * list.
     *
     * NOTHING NEEDS THE EAGER SUBZONE ANY MORE. The one question dispatch and
     * the worker app actually ask is "which of THIS SHOP's territories is this
     * address in", and TerritoryResolver.territoryForDelivery answers it
     * through a filtered query rather than by traversing this association.
     * Address.subzone stays as the stamp it always was - and stays LAZY, so
     * merely loading an address never touches another shop's map.
     */
    @org.springframework.data.jpa.repository.Query(
            "select a from Address a where a.customer.id = :customerId")
    List<Address> findByCustomerId(@org.springframework.data.repository.query.Param("customerId") Long customerId);

    @org.springframework.data.jpa.repository.Query(
            "select a from Address a where a.id = :id")
    java.util.Optional<Address> findByIdWithSubzone(
            @org.springframework.data.repository.query.Param("id") Long id);

    @org.springframework.data.jpa.repository.Query(
            value = "select a from Address a",
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