package com.gpstore.repository;

import java.util.List;

import com.gpstore.entity.Address;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressRepository extends JpaRepository<Address, Long> {

    /**
     * A customer's own addresses. No territory travels with them.
     *
     * <p>THIS QUERY ONCE FETCHED THE SUBZONE, and removing that fixed an
     * outage: an address is the CUSTOMER's row and spans every shop they buy
     * from, while {@code addresses.subzone_id} held one shop's answer - so a
     * customer whose address was stamped in Shop A's map, listing their
     * addresses while shopping at Shop B, had a shop-owned row from Shop A
     * loaded into Shop B's scope and the request was refused. They could not
     * read their own address list.
     *
     * <p>That column is gone (see Address, and the V70 migration). The stamp
     * lives in {@code address_territory_stamps}, one row per shop, and the
     * only people who ask for it are the ones dispatching an order - through
     * {@link com.gpstore.territory.AddressTerritory}, whose reads are narrowed
     * by the ordinary tenant filter. Loading an address can no longer reach
     * anybody's map.
     */
    @org.springframework.data.jpa.repository.Query(
            "select a from Address a where a.customer.id = :customerId")
    List<Address> findByCustomerId(@org.springframework.data.repository.query.Param("customerId") Long customerId);

    /**
     * Addresses belonging to the customers of the shop in scope.
     *
     * <p>FOR THE TERRITORY TOOLS, which write to addresses. Address is not a
     * {@code ShopOwned} entity - a home belongs to the person living in it -
     * so a page of {@code findAll()} in a shop-scoped operation is a page of
     * everybody's. Order IS shop-owned, so "whose customers are these" is a
     * question the tenant filter answers without naming a shop.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT a FROM Address a WHERE EXISTS ("
            + "  SELECT 1 FROM Order o WHERE o.customer = a.customer)")
    org.springframework.data.domain.Page<Address> findAllForCurrentShopCustomers(
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query(
            "select a from Address a where a.id = :id")
    java.util.Optional<Address> findByIdForRead(
            @org.springframework.data.repository.query.Param("id") Long id);

    @org.springframework.data.jpa.repository.Query(
            value = "select a from Address a",
            countQuery = "select count(a) from Address a")
    org.springframework.data.domain.Page<Address> findAllPaged(
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