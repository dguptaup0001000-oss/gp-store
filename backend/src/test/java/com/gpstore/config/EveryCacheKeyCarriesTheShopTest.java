package com.gpstore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NO CACHE ENTRY MAY BE SHARED BETWEEN TWO SHOPS.
 *
 * <p>WHAT KEEPS THEM APART. {@link CacheConfig#keyGenerator()} puts the tenant
 * in front of every key - {@code platform}, {@code shop:7}, or {@code
 * unscoped} - so Shop 7's browse results and Shop 8's cannot collide even
 * though the method arguments are identical. That generator is the DEFAULT
 * one, which Spring uses only when an annotation names neither {@code key} nor
 * {@code keyGenerator}.
 *
 * <p>WHICH MAKES IT ONE CARELESS EDIT FROM GONE. Writing
 * {@code @Cacheable(value = "productDetail", key = "#id")} is an ordinary,
 * reasonable-looking thing to do; it reads as a small optimisation and it
 * silently opts that cache out of the tenant namespace. Every shop then shares
 * one entry per product id, and the first shop to ask populates what the rest
 * read - a cross-tenant data leak with no failing test, no error in a log, and
 * nothing to see in review unless you already knew this rule existed.
 *
 * <p>{@code RecommendationHygieneTest} proves the namespace reaches the key
 * for ONE cache by constructing that key by hand. This asserts the property
 * for ALL of them, including the ones added next year.
 *
 * <p>If a cache genuinely needs a custom key one day, the fix is to make that
 * key start with the tenant - not to delete this test.
 */
@DisplayName("Every cache key carries the shop")
class EveryCacheKeyCarriesTheShopTest {

    private record CacheSite(String where, String key, String keyGenerator) {
        boolean overridesTheDefault() {
            return !key.isBlank() || !keyGenerator.isBlank();
        }
    }

    private static List<CacheSite> cacheSites() throws Exception {
        MetadataReaderFactory metadata = new CachingMetadataReaderFactory();
        Resource[] classes = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:com/gpstore/**/*.class");

        List<CacheSite> sites = new ArrayList<>();
        for (Resource resource : classes) {
            String name = metadata.getMetadataReader(resource).getClassMetadata().getClassName();
            Class<?> type;
            try {
                type = Class.forName(name, false, EveryCacheKeyCarriesTheShopTest.class.getClassLoader());
            } catch (Throwable notLoadable) {
                // A class we cannot load cannot carry an annotation we can
                // read. Skipping it is safe; the control below makes sure we
                // did not skip everything.
                continue;
            }
            Method[] methods;
            try {
                methods = type.getDeclaredMethods();
            } catch (Throwable incomplete) {
                continue;
            }
            for (Method method : methods) {
                Cacheable cacheable = method.getAnnotation(Cacheable.class);
                if (cacheable != null) {
                    sites.add(new CacheSite(
                            type.getSimpleName() + "#" + method.getName() + " @Cacheable",
                            cacheable.key(), cacheable.keyGenerator()));
                }
                CachePut put = method.getAnnotation(CachePut.class);
                if (put != null) {
                    sites.add(new CacheSite(
                            type.getSimpleName() + "#" + method.getName() + " @CachePut",
                            put.key(), put.keyGenerator()));
                }
            }
        }
        return sites;
    }

    @Test
    @DisplayName("no @Cacheable or @CachePut opts out of the tenant-aware key generator")
    void nothingOverridesTheTenantAwareKey() throws Exception {
        List<String> offenders = cacheSites().stream()
                .filter(CacheSite::overridesTheDefault)
                .map(site -> site.where() + " (key=\"" + site.key()
                        + "\", keyGenerator=\"" + site.keyGenerator() + "\")")
                .toList();

        assertTrue(offenders.isEmpty(),
                "These cache annotations name their own key, which bypasses "
                        + "CacheConfig.keyGenerator and therefore drops the shop out of the cache "
                        + "key. Every shop would then share one entry, and whichever shop asked "
                        + "first would decide what the others read. Put the tenant at the front of "
                        + "the custom key, or drop the key attribute and let the default generator "
                        + "do it: " + offenders);
    }

    @Test
    @DisplayName("control: the scanner actually finds the caches")
    void theScannerFindsSomething() throws Exception {
        // Without this, a scanner that matched no classes at all would make
        // the assertion above pass while proving precisely nothing.
        List<CacheSite> sites = cacheSites();
        assertTrue(sites.size() >= 10,
                "found only " + sites.size() + " cache annotations in com.gpstore, which is fewer "
                        + "than this application has - the scanner is not reading the classes and "
                        + "the assertion above is vacuous");

        assertEquals(1, sites.stream()
                        .filter(s -> s.where().startsWith("ShopHoursService#") )
                        .count(),
                "expected to find ShopHoursService's cache; if it was renamed or removed, update "
                        + "this control rather than deleting it");
    }
}
