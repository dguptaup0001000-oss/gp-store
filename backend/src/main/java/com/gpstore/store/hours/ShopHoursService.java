package com.gpstore.store.hours;

import com.gpstore.platform.Shop;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.TenantContext;
import com.gpstore.platform.TenantScope;
import com.gpstore.store.StoreScheduleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
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
        if (named == null || named.isBlank()) {
            return properties.getZone();
        }
        try {
            return ZoneId.of(named.trim());
        } catch (java.time.DateTimeException unknown) {
            log.warn("Shop {} names time zone '{}', which this JVM does not know; using {}.",
                    scope.shopId(), named, properties.getZone());
            return properties.getZone();
        }
    }
}
