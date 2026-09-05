package com.gpstore.platform;

import com.gpstore.entity.Address;
import com.gpstore.repository.AddressRepository;
import com.gpstore.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The shop a customer lands in when they have not opened a particular one.
 *
 * THE NEAREST SHOP THAT WILL DELIVER TO THEM, from their own saved address.
 * Not the first shop that registered, not a platform default, and not
 * anything they sent - the address is theirs and already on file, and the
 * radius is each shop's own.
 *
 * WHY THIS IS NOT IN TenantResolver. The resolver's job is to say what a
 * credential means, and it is deliberately small enough to read in one go.
 * Working out where somebody lives and which kiranas reach that far is a
 * different question with its own failure modes - no address yet, no pin on
 * the address, nobody in range - and each of those has to answer "no shop"
 * rather than "some shop".
 */
@Service
public class CustomerShopPreference {

    private final CurrentUser currentUser;
    private final AddressRepository addresses;
    private final ShopDiscovery discovery;

    public CustomerShopPreference(CurrentUser currentUser, AddressRepository addresses,
                                  ShopDiscovery discovery) {
        this.currentUser = currentUser;
        this.addresses = addresses;
        this.discovery = discovery;
    }

    /**
     * The nearest serving shop for the signed-in customer, if there is one.
     *
     * EMPTY IS A REAL ANSWER and the caller must treat it as one: a customer
     * with no address, no pin, or nobody in range has no shop, and inventing
     * one would put a shop in front of them that cannot deliver to them.
     */
    @Transactional(readOnly = true)
    public Optional<Long> shopForCurrentCustomer() {
        Long customerId;
        try {
            customerId = currentUser.customerId();
        } catch (RuntimeException noCredential) {
            return Optional.empty();
        }
        return shopFor(customerId);
    }

    @Transactional(readOnly = true)
    public Optional<Long> shopFor(Long customerId) {
        if (customerId == null) {
            return Optional.empty();
        }
        for (Address address : addressesOf(customerId)) {
            Optional<Shop> nearest =
                    discovery.nearestServing(address.getLatitude(), address.getLongitude());
            if (nearest.isPresent()) {
                return nearest.map(Shop::getId);
            }
        }
        return Optional.empty();
    }

    /**
     * The customer's addresses, default first.
     *
     * Tries the default before the rest: somebody who has a home address and
     * an office should get the shop nearest home, not nearest whichever row
     * the database happened to return first.
     */
    private List<Address> addressesOf(Long customerId) {
        List<Address> all = addresses.findByCustomerId(customerId);
        return all.stream()
                .sorted((a, b) -> Boolean.compare(
                        Boolean.TRUE.equals(b.getDefaultAddress()), Boolean.TRUE.equals(a.getDefaultAddress())))
                .toList();
    }
}
