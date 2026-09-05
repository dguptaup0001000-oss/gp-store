package com.gpstore.platform;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Turns the shop filter on for every persistence session opened inside a shop scope.
 *
 * WHY IT HAS TO HAPPEN HERE. A Hibernate filter is enabled on a Session, and
 * this application runs with spring.jpa.open-in-view=false - so a Session is
 * opened per transaction, not per request, and there is no servlet filter that
 * can reach one. Wrapping the EntityManagerFactory catches every session the
 * application will ever open: the ones Spring's transaction manager creates,
 * the ones @PersistenceContext creates for a call outside a transaction, and
 * any opened directly.
 *
 * NOTHING IS ENABLED WHEN THE SCOPE SPANS THE PLATFORM, and nothing is enabled
 * when no scope has been set at all - which is what keeps the outbox worker,
 * the stuck-refund sweep, the late-delivery flagger, payment expiry, R2
 * housekeeping and Flyway/bootstrap behaving exactly as they do today.
 *
 * SINGLE_SHOP DOES ENABLE IT, with Shop #1's id, and that is deliberate: the
 * enforcement path a marketplace depends on is then the same path the live
 * shop has been running on all along, rather than a code path that first
 * executes on the day a second merchant signs up.
 */
@Component
public class TenantFilterActivator implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof EntityManagerFactory)) {
            return bean;
        }
        return Proxy.newProxyInstance(
                bean.getClass().getClassLoader(),
                ClassUtils.getAllInterfacesForClass(bean.getClass()),
                new ScopeEverySession(bean));
    }

    /**
     * Enables the filter on one session, if the current scope names a shop.
     *
     * Public because the cross-tenant tests drive it directly - they open
     * sessions of their own to prove what a query sees from inside each
     * shop's scope, and must go through the same code the application does
     * rather than a copy of it.
     */
    public static void applyTo(EntityManager entityManager) {
        TenantScope scope = TenantContext.current();
        if (scope == null || scope.isPlatform()) {
            return;
        }
        entityManager.unwrap(Session.class)
                .enableFilter(ShopScopeFilter.NAME)
                .setParameter(ShopScopeFilter.SHOP_ID_PARAM, scope.requireShopId());
    }

    /**
     * The proxy's brain.
     *
     * equals and hashCode are answered here rather than forwarded, and that is
     * not tidiness: Spring keys the transactional EntityManager it binds to a
     * thread on the factory object itself. Forwarding equals would compare the
     * target to the proxy, come back false, and the lookup that is meant to
     * find the transaction's EntityManager would silently miss - a new
     * EntityManager per call, and no transaction where the code says there is
     * one.
     */
    private static final class ScopeEverySession implements InvocationHandler {

        private final Object target;

        private ScopeEverySession(Object target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("equals".equals(method.getName()) && method.getParameterCount() == 1) {
                return proxy == args[0];
            }
            if ("hashCode".equals(method.getName()) && method.getParameterCount() == 0) {
                return System.identityHashCode(proxy);
            }

            Object result;
            try {
                result = method.invoke(target, args);
            } catch (InvocationTargetException thrownByTheRealFactory) {
                throw thrownByTheRealFactory.getTargetException();
            }

            // A Hibernate Session is an EntityManager, so openSession() is
            // covered by the same branch as createEntityManager().
            if (result instanceof EntityManager entityManager) {
                applyTo(entityManager);
            }
            return result;
        }
    }
}
