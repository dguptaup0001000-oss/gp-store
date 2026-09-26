package com.gpstore.marketplace.synthetic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Controlled marketplace test data workflow writes 100 shops and 6000 listings into the
 * database a real shop trades on, and the only thing standing in front of it is the validate
 * job. That job once probed the live deployment with a single bare curl; a runner that could
 * not resolve api.gpstore.co.in failed it in twelve seconds, and the tempting "fix" for a
 * flaky network check is to make it softer. These assertions exist so that nobody can.
 */
@DisplayName("Controlled marketplace test data cannot be seeded without a verified live release")
class MarketplaceTestDataWorkflowGuardsTest {

    private static String read(String repoRelativePath) throws IOException {
        Path fromModule = Path.of("..", repoRelativePath);
        Path path = Files.isRegularFile(fromModule) ? fromModule : Path.of(repoRelativePath);
        return Files.readString(path);
    }

    /** The comments here explain the incident at length; only what RUNS is asserted on. */
    private static String withoutComments(String yaml) {
        return yaml.lines()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    @DisplayName("every guard the seed depends on is still in the validate job")
    void validateJobStillProvesTheWholeChain() throws IOException {
        String workflow = read(".github/workflows/seed-marketplace-test-data.yml");

        assertTrue(workflow.contains("[[ \"$TARGET_SHA\" =~ ^[0-9a-f]{40}$ ]]"),
                "the full 40-character SHA check was removed; a short SHA matches nothing");
        assertTrue(workflow.contains("[[ \"$CONFIRMATION\" == \"MARKETPLACE_TEST_100_SHOPS_V1\" ]]"),
                "the exact confirmation string is the batch identity cleanup keys on");
        assertTrue(workflow.contains("[[ \"$(git rev-parse origin/main)\" == \"$TARGET_SHA\" ]]"),
                "the requested SHA must still be the current tip of origin/main");
        assertTrue(workflow.contains("deploy/production/verify-public-release-sha.sh"),
                "the live-release verifier must run before anything may be seeded");
        assertTrue(workflow.contains("needs: validate"),
                "the operating job must still depend on validation");
    }

    @Test
    @DisplayName("the live-release check is retried, not asked once")
    void theLiveReleaseCheckIsNotASingleBareRequest() throws IOException {
        String workflow = read(".github/workflows/seed-marketplace-test-data.yml");

        assertFalse(withoutComments(workflow).contains("curl"),
                "the version probe must go through the retrying verifier, not a bare curl in YAML");
        assertTrue(workflow.contains("VERIFY_ATTEMPTS"),
                "the verifier must be given a bounded attempt budget");
        assertFalse(workflow.contains("continue-on-error"),
                "no step in this workflow may continue past its own failure");
    }

    @Test
    @DisplayName("an unreachable production is a failure, never a pass")
    void unreachableProductionCannotBeMistakenForVerified() throws IOException {
        String verifier = read("deploy/production/verify-public-release-sha.sh");

        assertTrue(verifier.contains("readonly VERIFIED=0")
                        && verifier.contains("readonly WRONG_RELEASE=1")
                        && verifier.contains("readonly NEVER_ANSWERED=2"),
                "the three outcomes must stay distinct: verified, wrong release, never answered");
        assertTrue(verifier.contains("return \"$NEVER_ANSWERED\""),
                "exhausting the attempt budget must return the unreachable code, not success");
        assertTrue(verifier.contains("VERIFY_ATTEMPTS") && verifier.contains("sleep \"$gap\""),
                "the retry must be bounded and must back off between attempts");
        assertTrue(verifier.contains("--doh-url") && verifier.contains("--resolve"),
                "a resolver that keeps failing must be worked around, not only asked again");

        String workflow = read(".github/workflows/seed-marketplace-test-data.yml");
        assertTrue(workflow.contains("exit \"$status\""),
                "the verifier's exit status is the validation verdict and must be propagated");
    }

    @Test
    @DisplayName("the running JAR's own commit is still part of the identity")
    void bothSourceAndBinaryCommitsMustMatch() throws IOException {
        String verifier = read("deploy/production/verify-public-release-sha.sh");

        assertTrue(verifier.contains("body.get(\"gitCommit\") != expected"),
                "the deployed source commit must still be compared");
        assertTrue(verifier.contains("body.get(\"binaryGitCommit\") != expected"),
                "the commit baked into the running JAR must still be compared - this is "
                        + "VersionGuard's refusal made from outside");
        assertTrue(verifier.contains("body.get(\"environment\") != expected_env"),
                "a non-production deployment answering on this name must not satisfy the guard");
    }

    @Test
    @DisplayName("the verifier's own decisions are proved on every CI run and every deploy")
    void theVerifierIsSelfTestedInCi() throws IOException {
        String selfTest = "VERIFY_RELEASE_SHA_SELFTEST=1";

        assertTrue(read(".github/workflows/ci.yml").contains(selfTest),
                "CI must run the verifier's self-test");
        assertTrue(read(".github/workflows/deploy-production.yml").contains(selfTest),
                "the deploy must run the verifier's self-test, so a change to it cannot "
                        + "reach production untested");
    }
}
