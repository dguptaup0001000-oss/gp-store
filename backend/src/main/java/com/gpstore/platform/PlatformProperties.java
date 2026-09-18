package com.gpstore.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deployment-wide platform settings.
 *
 * <p>DEFAULTS TO THE MARKETPLACE, AND THAT IS A REVERSAL. This used to default
 * to SINGLE_SHOP, reasoning that "turning the marketplace on has to be a
 * deliberate act". That was right when GP-STORE was one kirana. It became
 * exactly backwards once real merchants were onboarded, because SINGLE_SHOP is
 * not the narrow mode - it is the wide one:
 *
 * <ul>
 *   <li>{@code CatalogDefinitionAuthorization} hands catalogue definition to
 *       anyone holding CATALOG_MANAGE, which every shopkeeper holds;</li>
 *   <li>{@code TenantResolver} returns before the branch that gives a platform
 *       administrator platform scope, so Super Admin is scoped to Shop #1;</li>
 *   <li>browse surfaces stop narrowing to the acting shop's shelf.</li>
 * </ul>
 *
 * <p>So an absent or misspelt {@code PLATFORM_MODE} silently chose the
 * permissive setting. The rule now is: <b>absent means strict, malformed means
 * refuse to start.</b> A deployment that cannot say what it is does not get to
 * be the permissive one.
 *
 * <p>SINGLE_SHOP is not removed - a genuine one-shop deployment is a real thing
 * and the mode still serves it. It simply has to be asked for.
 */
@Component
public class PlatformProperties {

    private final PlatformMode mode;
    private final String firstShopCode;
    private final String firstShopName;
    private final String firstMerchantName;

    public PlatformProperties(
            @Value("${platform.mode:}") String mode,
            @Value("${platform.first-shop.code:SHOP-1}") String firstShopCode,
            @Value("${platform.first-shop.name:}") String firstShopName,
            @Value("${platform.first-merchant.legal-name:}") String firstMerchantName) {

        this.mode = parse(mode);
        this.firstShopCode = firstShopCode;
        this.firstShopName = firstShopName;
        this.firstMerchantName = firstMerchantName;
    }

    /**
     * The mode this deployment runs, or a refusal to run at all.
     *
     * <p>THE OLD VERSION SWALLOWED TYPOS AND CALLED IT FAILING CLOSED. An
     * unrecognised value returned SINGLE_SHOP, on the reasoning that a typo
     * "must not be able to put a deployment into a mode it was not configured
     * for". The half that was missed: the fallback itself put the deployment
     * into a mode nobody configured either - and on a live marketplace that
     * fallback is the widest mode there is. {@code PLATFORM_MODE=MULTI_SHOP},
     * the most natural way to get this wrong, read as SINGLE_SHOP.
     *
     * <p>A boot that cannot tell what it is supposed to be must stop, loudly,
     * naming what it was given and what it will accept. Nobody debugs a
     * marketplace that is quietly one shop.
     */
    private static PlatformMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            // Absent is the strict answer, not the convenient one. See the
            // class comment: SINGLE_SHOP is the permissive mode now.
            return PlatformMode.MULTI_SHOP_PRODUCTION;
        }
        try {
            return PlatformMode.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalStateException(
                    "platform.mode is set to \"" + raw.trim() + "\", which is not a mode. "
                            + "Use one of: SINGLE_SHOP, MULTI_SHOP_DEMO, "
                            + "MULTI_SHOP_PRODUCTION. Leave it unset for "
                            + "MULTI_SHOP_PRODUCTION, which is what GP-STORE runs.");
        }
    }

    public PlatformMode getMode() { return mode; }
    public String getFirstShopCode() { return firstShopCode; }
    public String getFirstShopName() { return firstShopName; }
    public String getFirstMerchantName() { return firstMerchantName; }
}
