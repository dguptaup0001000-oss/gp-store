package com.gpstore.marketplace.synthetic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * One-shot entry point, present only when the explicit CLI profile is active.
 * It is absent from ordinary production startup and never exposes an HTTP route.
 */
@Component
@Profile("marketplace-test-data-cli")
@Order(Ordered.LOWEST_PRECEDENCE)
public class MarketplaceTestDataCliRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceTestDataCliRunner.class);
    private final MarketplaceTestDataSeeder seeder;

    public MarketplaceTestDataCliRunner(MarketplaceTestDataSeeder seeder) {
        this.seeder = seeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            String action = requiredOption(args, "gpstore.synthetic-data.action");
            MarketplaceTestDataSeeder.Operation operation =
                    MarketplaceTestDataSeeder.Operation.valueOf(action.toUpperCase(java.util.Locale.ROOT));
            String batch = requiredOption(args, "gpstore.synthetic-data.batch");
            String expectedCommit = args.containsOption("gpstore.synthetic-data.expected-commit")
                    ? args.getOptionValues("gpstore.synthetic-data.expected-commit").getFirst() : null;
            MarketplaceTestDataSeeder.Result result = seeder.execute(
                    new MarketplaceTestDataSeeder.Request(operation, batch, expectedCommit));
            log.info("Marketplace synthetic dataset operation complete: operation={}, batch={}, "
                            + "shops={}, merchants={}, products={}, variants={}, listings={}, "
                            + "BUY_ONLINE={}, VISIT_TO_BUY={}, SERVICE_AT_SHOP={}, withImages={}, "
                            + "withoutImages={}, alreadyPresent={}",
                    operation, batch, result.shops(), result.merchants(), result.products(),
                    result.variants(), result.listings(), result.buyOnline(), result.visitToBuy(),
                    result.serviceAtShop(), result.withImages(), result.withoutImages(), result.alreadyPresent());
            System.exit(0);
        } catch (Exception failure) {
            log.error("Marketplace synthetic dataset operation refused or failed; transaction rolled back.", failure);
            System.exit(2);
        }
    }

    private static String requiredOption(ApplicationArguments args, String name) {
        if (!args.containsOption(name) || args.getOptionValues(name) == null
                || args.getOptionValues(name).size() != 1) {
            throw new IllegalArgumentException("Pass exactly one --" + name + " value.");
        }
        return args.getOptionValues(name).getFirst();
    }
}
