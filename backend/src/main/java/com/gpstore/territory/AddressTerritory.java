package com.gpstore.territory;

import com.gpstore.entity.Address;
import com.gpstore.entity.DeliverySubzone;
import com.gpstore.repository.DeliverySubzoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * One shop's answer to "which of my territories is this house in?".
 *
 * <p>EVERY READ AND WRITE OF A TERRITORY STAMP GOES THROUGH HERE, which is the
 * point: the rule that a stamp belongs to one shop is then a property of one
 * object rather than a thing five call sites each have to remember. See
 * {@link AddressTerritoryStamp} for what the two columns on {@code addresses}
 * did to each other before this existed.
 *
 * <p>WHO WRITES A STAMP, AND WHEN:
 *
 * <ul>
 *   <li>the address is saved or edited while this shop is in scope - the
 *       single-shop path, unchanged from before;</li>
 *   <li>this shop dispatches an order to it for the first time - which is the
 *       moment a second shop on a marketplace first has a reason to have an
 *       answer at all;</li>
 *   <li>an administrator pins it by hand, for their own shop;</li>
 *   <li>an administrator re-resolves their own map.</li>
 * </ul>
 *
 * <p>Nothing else moves a stamp once it exists. That is what a rider's local
 * knowledge is worth.
 */
@Service
public class AddressTerritory {

    private final AddressTerritoryStampRepository stamps;
    private final TerritoryResolver resolver;
    private final DeliverySubzoneRepository subzones;
    private final com.gpstore.repository.AddressRepository addresses;

    public AddressTerritory(AddressTerritoryStampRepository stamps,
                            TerritoryResolver resolver,
                            DeliverySubzoneRepository subzones,
                            com.gpstore.repository.AddressRepository addresses) {
        this.stamps = stamps;
        this.resolver = resolver;
        this.subzones = subzones;
        this.addresses = addresses;
    }

    /**
     * The shop asking, or null when nothing can say which shop that is.
     *
     * <p>The scope on the thread, or - on a single-shop deployment with no
     * request behind the work, which is how every scheduled job and every test
     * fixture writes rows - the only shop there is. The platform console has
     * no one map of its own, so it gets null and reads fall back to computing
     * an answer rather than pretending to have stamped one.
     */
    public Long actingShopId() {
        return com.gpstore.platform.TenantDefaults.shopIdIfKnown().orElse(null);
    }

    /** This shop's stamp for an address, if it has ever made one. */
    @Transactional(readOnly = true)
    public Optional<AddressTerritoryStamp> stampFor(Long addressId) {
        if (addressId == null || actingShopId() == null) {
            return Optional.empty();
        }
        return stamps.findForAddress(addressId);
    }

    /**
     * The territory this shop would deliver to, WITHOUT writing anything.
     *
     * <p>The stamp when there is one - including a stamp that says "no
     * territory", because a shop that has looked and found none has an answer
     * and should not keep looking - and this shop's live map otherwise.
     */
    @Transactional(readOnly = true)
    public Optional<DeliverySubzone> territoryFor(Address address) {
        if (address == null) {
            return Optional.empty();
        }
        Optional<AddressTerritoryStamp> stamp = stampFor(address.getId())
                .filter(s -> s.describes(address.getLatitude(), address.getLongitude()));
        if (stamp.isPresent()) {
            return Optional.ofNullable(stamp.get().getSubzone());
        }
        // No stamp, or one made from coordinates the customer has since
        // corrected - which is an answer about somewhere else.
        return resolveFromMap(address);
    }

    /**
     * The territory this shop will deliver to, recording the answer if this is
     * the first time it has needed one.
     *
     * <p>THE WRITE IS THE POINT, and it is why this is a separate method from
     * {@link #territoryFor}. Dispatch is where a shop first commits to a
     * territory for a house; recording it there is what makes the answer
     * stable afterwards, so that a later boundary edit does not silently move
     * a rider's round. Callers are inside their own write transaction
     * already - see DeliveryService - and read-only callers use
     * {@code territoryFor}, which never writes.
     */
    @Transactional
    public Optional<DeliverySubzone> territoryForDispatch(Address address) {
        if (address == null || address.getId() == null || actingShopId() == null) {
            return territoryFor(address);
        }
        AddressTerritoryStamp existing = stamps.findForAddress(address.getId()).orElse(null);
        if (existing != null && existing.describes(address.getLatitude(), address.getLongitude())) {
            return Optional.ofNullable(existing.getSubzone());
        }
        DeliverySubzone resolved = resolveFromMap(address).orElse(null);
        if (existing == null) {
            stamps.save(new AddressTerritoryStamp(address.getId(), resolved, false,
                    address.getLatitude(), address.getLongitude()));
        } else {
            // THE HOUSE MOVED, SO THE PIN IS ABOUT SOMEWHERE ELSE. A person
            // placed the OLD point by hand; they have not said anything about
            // this one, and keeping the lock would hold a customer in a
            // territory chosen for an address they have told us was wrong.
            existing.setSubzone(resolved);
            existing.setLocked(false);
            existing.resolvedAt(address.getLatitude(), address.getLongitude());
            stamps.save(existing);
        }
        return Optional.ofNullable(resolved);
    }

    /**
     * Records this shop's answer for an address it has just been given.
     *
     * <p>Called when an address is created or edited in a shop's context. A
     * PINNED stamp is left alone: a person's judgement about a house outranks
     * anything the polygons say, including after the customer nudges their own
     * coordinates.
     */
    @Transactional
    public void stampForCurrentShop(Address address) {
        if (address == null || address.getId() == null || actingShopId() == null) {
            return;
        }
        AddressTerritoryStamp stamp = stamps.findForAddress(address.getId()).orElse(null);
        if (stamp != null && stamp.isLocked()
                && stamp.describes(address.getLatitude(), address.getLongitude())) {
            // A hand-placed pin about THIS point outranks the map.
            return;
        }
        DeliverySubzone resolved = resolveFromMap(address).orElse(null);
        if (stamp == null) {
            stamps.save(new AddressTerritoryStamp(address.getId(), resolved, false,
                    address.getLatitude(), address.getLongitude()));
            return;
        }
        stamp.setSubzone(resolved);
        stamp.setLocked(false);
        stamp.resolvedAt(address.getLatitude(), address.getLongitude());
        stamps.save(stamp);
    }

    /**
     * Pins an address into one of THIS shop's territories, by hand.
     *
     * <p>The subzone decides the shop: it is shop-owned, so a stamp made from
     * it can only ever belong to the shop that drew it - which is also how a
     * platform administrator pins on a named shop's behalf without having to
     * say which shop they mean.
     */
    @Transactional
    public AddressTerritoryStamp pin(Long addressId, DeliverySubzone subzone) {
        Long owningShop = subzone != null ? subzone.getShopId() : actingShopId();
        if (owningShop == null) {
            throw new com.gpstore.exception.BadRequestException(
                    "Say which territory this address belongs to. Clearing a pin is done "
                            + "from the shop whose map it was made on.");
        }
        // NAMED, NOT FILTERED. A platform administrator pinning has no shop on
        // the thread, so a filtered lookup would return whichever shop's row
        // came first and this pin would land on somebody else's stamp. The
        // shop comes from the territory being pinned into.
        AddressTerritoryStamp existing =
                stamps.findForAddressInShop(addressId, owningShop).orElse(null);
        AddressTerritoryStamp stamp;
        if (existing == null) {
            stamp = new AddressTerritoryStamp(addressId, subzone, true);
            stamp.setShopId(owningShop);
        } else {
            stamp = existing;
            stamp.setSubzone(subzone);
            stamp.setLocked(true);
        }
        // The pin is about the house as it stands now, so record the point it
        // was made for - if the customer later moves their pin, this judgement
        // stops being about their house and is resolved again.
        addresses.findById(addressId).ifPresent(
                a -> stamp.resolvedAt(a.getLatitude(), a.getLongitude()));
        return stamps.save(stamp);
    }

    /**
     * WHY THERE IS NO "FORGET EVERY SHOP'S STAMP" HERE ANY MORE.
     *
     * <p>An earlier version of this class had one, called from the address
     * edit path: the customer moves their pin, so go and delete every shop's
     * answer. It worked, and it was the wrong shape - it made one shop's
     * request responsible for telling every other shop something, and it
     * depended on that path noticing the coordinates had changed at all, which
     * is brittle when the caller hands back the same object it was given.
     *
     * <p>A stamp now records the POINT it was resolved from, so each shop
     * discovers staleness from its own row, at the moment it asks. Nothing has
     * to be told anything.
     */

    /**
     * Re-runs THIS SHOP'S map over the houses this shop has answers for.
     *
     * <p>WHICH HOUSES, AND WHY THOSE. The set is this shop's own stamps - the
     * addresses it has already committed to a territory for. That is what
     * "re-resolve" means once a stamp belongs to one shop: refresh my answers.
     * It used to page {@code addresses} itself, which on a marketplace is every
     * customer on GP-STORE and is how one merchant's button came to re-stamp
     * every other merchant's customers.
     *
     * <p>A house this shop has never dispatched to has no stamp and is not
     * walked; it gets an answer the first time the shop actually needs one.
     * A PINNED stamp is left alone - that is what pinning is for.
     *
     * @return how many stamps actually moved, which is what the administrator
     *         is told: a re-resolve that moves nobody is a map that has not
     *         changed where anyone lives.
     */
    @Transactional
    public int reresolveForCurrentShop(int pageSize, java.util.function.LongFunction<Address> byId) {
        int moved = 0;
        int page = 0;
        int safePageSize = Math.min(Math.max(pageSize, 1), 500);

        while (true) {
            org.springframework.data.domain.Page<AddressTerritoryStamp> slice =
                    stamps.findAllForCurrentShop(
                            org.springframework.data.domain.PageRequest.of(page, safePageSize));
            if (slice.isEmpty()) {
                break;
            }
            for (AddressTerritoryStamp stamp : slice.getContent()) {
                if (stamp.isLocked()) {
                    continue;
                }
                Address address = byId.apply(stamp.getAddressId());
                if (address == null) {
                    continue;
                }
                Long before = stamp.getSubzone() == null ? null : stamp.getSubzone().getId();
                DeliverySubzone after = resolveFromMap(address).orElse(null);
                Long afterId = after == null ? null : after.getId();
                if (!java.util.Objects.equals(before, afterId)) {
                    stamp.setSubzone(after);
                    stamps.save(stamp);
                    moved++;
                }
            }
            if (!slice.hasNext()) {
                break;
            }
            page++;
        }
        return moved;
    }

    private Optional<DeliverySubzone> resolveFromMap(Address address) {
        return resolver.resolveSubzoneId(address.getLatitude(), address.getLongitude())
                .flatMap(subzones::findInScope);
    }
}
