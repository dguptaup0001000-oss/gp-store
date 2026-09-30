package com.gpstore.store.hours;

import com.gpstore.platform.Shop;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantScope;
import com.gpstore.store.StoreScheduleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the shop in scope's trading hours, as a value the calculator can use.
 *
 * <p>ONE QUERY EACH, BOUNDED BY THE LOOKAHEAD. The week is seven days' worth
 * of rows however many sessions a shop keeps, and the overrides are read for
 * the range the schedule can actually see - the same shape, and the same
 * reason, as the closed-day query beside it: asking the database "what are
 * the hours on this date" inside the day-scanning loop is thirty round trips
 * to answer one status request, on the hot path of every product page.
 *
 * <p>NOTHING TAKES A SHOP ID. Both repositories are JPQL over shop-owned
 * entities, so Hibernate's filter narrows them to the shop the credential
 * resolved to. That is also why this is the class that reads the zone off the
 * Shop row: the shop is already decided, and looking it up here keeps every
 * caller from having to carry one.
 */
@Service
public class ShopHoursService {

    private static final Logger log = LoggerFactory.getLogger(ShopHoursService.class);

    private final ShopBusinessHoursRepository weekly;
    private final ShopHoursOverrideRepository overrides;
    private final ShopRepository shops;
    private final StoreScheduleProperties properties;

    public ShopHoursService(ShopBusinessHoursRepository weekly,
                            ShopHoursOverrideRepository overrides,
                            ShopRepository shops,
                            StoreScheduleProperties properties) {
        this.weekly = weekly;
        this.overrides = overrides;
        this.shops = shops;
        this.properties = properties;
    }

    /**
     * This shop's hours, over a date range wide enough for the schedule scan.
     *
     * <p>FAILS OPEN, ON PURPOSE, and this is one of the few places that is the
     * right direction. If the hours cannot be read, falling back to the
     * deployment's configuration keeps the shop trading on the hours it traded
     * on before this table existed; treating the shop as closed would stop it
     * taking orders because of a database hiccup. It is the same call
     * DeliveryScheduleService already makes for the closures table, and for
     * the same reason.
     */
    /**
     * CACHED, and it has to be. Building this reads three rows sets - the
     * week, the overrides in range, and the shop's zone - and the schedule is
     * rebuilt on every question the order path asks: is the shop accepting,
     * what date is this for, what is the status. Without a cache that is
     * three extra queries per checkout, which CheckoutPerformanceTest caught
     * the moment this was introduced.
     *
     * <p>Safe to cache in a way a price or a stock count would not be: a
     * shop's week changes when a shopkeeper edits it, and the edit evicts.
     * Keyed by shop (CacheConfig.keyGenerator) and by the date range, which
     * is derived from today and so is stable for the day.
     */
    @Cacheable(value = "shopHours", sync = true)
    @Transactional(readOnly = true)
    public ShopHours forCurrentShop(LocalDate from, LocalDate to) {
        try {
            Map<DayOfWeek, List<ShopHours.OpenPeriod>> week = new EnumMap<>(DayOfWeek.class);
            for (ShopBusinessHours row : weekly.wholeWeek()) {
                week.computeIfAbsent(row.day(), day -> new ArrayList<>())
                        .add(new ShopHours.OpenPeriod(row.getOpensAt(), row.getClosesAt()));
            }

            Map<LocalDate, List<ShopHours.OpenPeriod>> byDate = new HashMap<>();
            for (ShopHoursOverride row : overrides.findBetween(from, to)) {
                byDate.computeIfAbsent(row.getOnDate(), date -> new ArrayList<>())
                        .add(new ShopHours.OpenPeriod(row.getOpensAt(), row.getClosesAt()));
            }

            return ShopHours.of(zoneOfCurrentShop(), week, byDate, properties);
        } catch (Exception ex) {
            log.warn("Could not read this shop's hours; trading on the deployment's configured "
                    + "hours instead: {}", ex.toString());
            return ShopHours.deploymentDefault(properties);
        }
    }

    /**
     * Loads every discovered storefront's trading hours in two bounded reads.
     *
     * <p>This is deliberately separate from {@link #forCurrentShop}: checkout
     * and writes remain scoped to exactly one shop, while a public marketplace
     * list has already resolved the precise shops it may expose. Calling the
     * scoped cached method in a loop caused a cache miss and up to three SQL
     * reads per shop. Worse, its {@code sync=true} cache lock was waited on
     * while the controller still held a transaction connection. Under load
     * all twenty Hikari connections became idle-in-transaction and useful CPU
     * work stopped. This method performs no per-shop repository calls and
     * returns detached value objects for transaction-free DTO computation.
     */
    @Transactional(readOnly = true)
    public Map<Long, ShopHours> forShops(Collection<Shop> discovered,
                                         LocalDate from, LocalDate to) {
        if (discovered == null || discovered.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<Long, Shop> byId = new LinkedHashMap<>();
        for (Shop shop : discovered) {
            if (shop != null && shop.getId() != null) {
                byId.putIfAbsent(shop.getId(), shop);
            }
        }
        if (byId.isEmpty()) {
            return Map.of();
        }

        Map<Long, Map<DayOfWeek, List<ShopHours.OpenPeriod>>> weeks = new HashMap<>();
        Map<Long, Map<LocalDate, List<ShopHours.OpenPeriod>>> byDates = new HashMap<>();
        try {
            for (ShopBusinessHours row : weekly.findForShops(byId.keySet())) {
                weeks.computeIfAbsent(row.getShopId(), ignored -> new EnumMap<>(DayOfWeek.class))
                        .computeIfAbsent(row.day(), ignored -> new ArrayList<>())
                        .add(new ShopHours.OpenPeriod(row.getOpensAt(), row.getClosesAt()));
            }
            for (ShopHoursOverride row : overrides.findBetweenForShops(byId.keySet(), from, to)) {
                byDates.computeIfAbsent(row.getShopId(), ignored -> new HashMap<>())
                        .computeIfAbsent(row.getOnDate(), ignored -> new ArrayList<>())
                        .add(new ShopHours.OpenPeriod(row.getOpensAt(), row.getClosesAt()));
            }
        } catch (RuntimeException ex) {
            log.warn("Could not batch-read marketplace shop hours; using deployment hours for "
                    + "this storefront list: {}", ex.toString());
            Map<Long, ShopHours> fallback = new LinkedHashMap<>();
            byId.forEach((id, shop) -> fallback.put(id,
                    ShopHours.of(zoneOf(shop), Map.of(), Map.of(), properties)));
            return Map.copyOf(fallback);
        }

        Map<Long, ShopHours> result = new LinkedHashMap<>();
        byId.forEach((id, shop) -> result.put(id, ShopHours.of(zoneOf(shop),
                weeks.getOrDefault(id, Map.of()),
                byDates.getOrDefault(id, Map.of()), properties)));
        return Map.copyOf(result);
    }

    /**
     * Called after any write that changes what {@link #forCurrentShop} would
     * answer: the week, an override, or the shop's zone.
     *
     * <p>All shops' entries, for the reason ShopShelfCache spells out -
     * @CacheEvict clears a region rather than a key prefix, and a shop whose
     * hours are ten minutes stale is a shop telling customers the wrong
     * thing about when their shopping arrives.
     */
    @CacheEvict(value = "shopHours", allEntries = true)
    public void hoursChanged() {
        // The annotation is the whole method.
    }

    /**
     * The shop's own zone, or the deployment's when it has not set one.
     *
     * <p>A BAD ZONE NAME IS NOT A BROKEN SHOP. Shop.timeZone is free text on a
     * row an admin typed; an unparseable value falls back rather than throwing
     * on the order path, which is the same choice StoreScheduleProperties
     * makes about its own configured zone.
     */
    private ZoneId zoneOfCurrentShop() {
        TenantScope scope = TenantContext.current();
        if (scope == null || scope.isPlatform() || scope.shopId() == null) {
            return properties.getZone();
        }
        String named = shops.findById(scope.shopId()).map(Shop::getTimeZone).orElse(null);
        return zoneOf(scope.shopId(), named);
    }

    private ZoneId zoneOf(Shop shop) {
        return zoneOf(shop == null ? null : shop.getId(),
                shop == null ? null : shop.getTimeZone());
    }

    private ZoneId zoneOf(Long shopId, String named) {
        if (named == null || named.isBlank()) {
            return properties.getZone();
        }
        try {
            return ZoneId.of(named.trim());
        } catch (java.time.DateTimeException unknown) {
            log.warn("Shop {} names time zone '{}', which this JVM does not know; using {}.",
                    shopId, named, properties.getZone());
            return properties.getZone();
        }
    }
}
