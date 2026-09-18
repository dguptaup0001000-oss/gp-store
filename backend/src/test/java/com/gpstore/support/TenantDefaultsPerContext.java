package com.gpstore.support;

import com.gpstore.platform.PlatformProperties;
import com.gpstore.platform.ShopRepository;
import com.gpstore.platform.TenantDefaults;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Gives each test the tenant defaults of ITS OWN application context.
 *
 * <p>THE BUG THIS EXISTS TO KILL, AND IT IS A REAL ONE. {@code TenantDefaults}
 * is static, and it has to be: JPA entity listeners are built by Hibernate
 * rather than by Spring, so the {@code @PrePersist} that stamps a shop on a
 * new row cannot be injected with anything. In production that is harmless -
 * one process, one context, one installation at startup.
 *
 * <p>A test run is not one context. Surefire keeps one JVM and Spring caches a
 * context per distinct configuration, so a suite holds a dozen of them. Each
 * one installs its own defaults as it is built, into the same static field -
 * so the LAST context created wins for every test that runs afterwards,
 * whichever context that test belongs to. A class that declares
 * {@code platform.mode=SINGLE_SHOP} would pass alone and fail in the suite,
 * with an error naming a mode it never asked for. That is not a flake to be
 * re-run; it is one test's configuration silently applied to another's.
 *
 * <p>Re-installing from the context that owns the test, before every test
 * method, makes the static field say what the running test's configuration
 * says. Nothing in production changes: {@code TenantDefaultsInstaller} still
 * installs once at startup, and this listener exists only on the test
 * classpath.
 *
 * <p>Registered for every Spring test through
 * {@code META-INF/spring/org.springframework.test.context.TestExecutionListener.imports},
 * so a test does not have to remember to ask for it - remembering is the part
 * that failed.
 */
public class TenantDefaultsPerContext implements TestExecutionListener {

    @Override
    public void beforeTestMethod(TestContext testContext) {
        ApplicationContext context = testContext.getApplicationContext();
        if (!context.containsBean("platformProperties")
                && context.getBeanNamesForType(PlatformProperties.class).length == 0) {
            return; // a slice that does not carry the platform configuration
        }
        PlatformProperties platform = context.getBean(PlatformProperties.class);
        String[] shopRepositories = context.getBeanNamesForType(ShopRepository.class);
        if (shopRepositories.length == 0) {
            return;
        }
        ShopRepository shops = context.getBean(ShopRepository.class);
        TenantDefaults.install(platform.getMode(),
                () -> shops.findByCode(platform.getFirstShopCode())
                        .map(shop -> shop.getId())
                        .orElse(null));
    }
}
