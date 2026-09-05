/**
 * Multi-tenancy: merchants, shops, and the machinery that keeps one shop's
 * data away from another's.
 *
 * The filter declared here is applied to every {@link com.gpstore.platform.ShopOwned}
 * entity and enabled per Hibernate session by
 * {@link com.gpstore.platform.TenantFilterActivator}. Declaring it once, in
 * one place, is deliberate: a filter defined per entity is a filter that can
 * be defined differently per entity.
 */
@FilterDef(
        name = ShopScopeFilter.NAME,
        parameters = @ParamDef(name = ShopScopeFilter.SHOP_ID_PARAM, type = Long.class)
)
package com.gpstore.platform;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
