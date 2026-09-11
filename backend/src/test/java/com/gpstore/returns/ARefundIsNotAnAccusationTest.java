package com.gpstore.returns;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A NORMAL REFUND MUST NOT AUTOMATICALLY BECOME A MERCHANT VIOLATION.
 *
 * <p>THE BUSINESS RULE THIS PROTECTS. A shop that takes a return and gives
 * the money back has done the right thing. If refunding quietly fed the
 * governance ladder, the shops that treat customers best would collect the
 * most warnings, and the rational response for a merchant would be to refuse
 * refunds - which is the exact opposite of what the ladder is for.
 *
 * <p>WHY THIS IS A SOURCE SCAN RATHER THAN A SCENARIO. The property is an
 * ABSENCE: no call, anywhere on the return or refund path, into governance.
 * A scenario test can only show that one particular refund raised no action;
 * this fails the build the moment anybody wires the two together, which is
 * the mistake worth catching.
 *
 * <p>IT IS NOT A BAN ON DISCIPLINE. GP-STORE may still act on a shop that
 * refuses legitimate refunds - that is a decision a person makes, with
 * evidence, through the governance API. What may not exist is the automatic
 * edge from "money went back" to "the shop is in trouble".
 */
class ARefundIsNotAnAccusationTest {

    private static final Path MAIN = Path.of("src/main/java/com/gpstore");

    /** Every place a refund or a return is actually carried out. */
    private static final List<String> THE_REFUND_PATH = List.of(
            "returns/ReturnService.java",
            "service/PaymentService.java",
            "payment/GatewayPaymentService.java");

    @Test
    @DisplayName("nothing on the refund path reaches into governance")
    void refundingDoesNotRaiseAGovernanceAction() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String file : THE_REFUND_PATH) {
            Path path = MAIN.resolve(file);
            if (!Files.exists(path)) {
                continue;
            }
            String source = Files.readString(path);
            if (source.contains("MerchantGovernance")
                    || source.contains("com.gpstore.governance")
                    || source.contains("GovernanceLevel")) {
                offenders.add(file);
            }
        }

        assertTrue(offenders.isEmpty(),
                "A refund must not automatically become a merchant violation, but "
                        + offenders + " now reaches into governance. If GP-STORE has "
                        + "decided that some refunds should count against a shop, that "
                        + "is a policy decision and belongs behind an explicit, "
                        + "evidenced governance action - not on the path every honest "
                        + "refund takes.");
    }

    /**
     * THE POSITIVE CONTROL, and the test above is worthless without it.
     *
     * <p>A scan that finds nothing proves nothing unless (a) the files it
     * searched are the real ones and (b) the string it searched for is one
     * that exists to be found. Governance turns out to be entirely
     * self-contained - NOTHING outside its own package calls it, which is a
     * stronger fact than the one being asserted above and worth recording -
     * so the control anchors on the package itself rather than on a caller.
     */
    @Test
    @DisplayName("the files scanned are real and the search term is one that exists")
    void theScanWouldHaveFoundIt() throws IOException {
        for (String file : THE_REFUND_PATH) {
            Path path = MAIN.resolve(file);
            assertTrue(Files.exists(path),
                    file + " is not where this test thinks it is, so the scan above "
                            + "searched nothing. Fix the path rather than the assertion.");
            assertTrue(Files.readString(path).length() > 1000,
                    file + " is suspiciously small - the scan may be reading a stub.");
        }

        assertTrue(Files.readString(MAIN.resolve("governance/MerchantGovernance.java"))
                        .contains("MerchantGovernance"),
                "The term the scan looks for does not appear even in the governance "
                        + "service itself, so a clean scan means nothing.");
    }
}
