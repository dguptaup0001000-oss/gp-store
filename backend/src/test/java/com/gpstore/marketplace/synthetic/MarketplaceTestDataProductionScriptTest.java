package com.gpstore.marketplace.synthetic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Production marketplace test-data runner preserves build identity")
class MarketplaceTestDataProductionScriptTest {

    @Test
    @DisplayName("the one-shot process uses the already-deployed SHA image and runtime SHA")
    void oneShotUsesVerifiedImageAndRuntimeCommit() throws IOException {
        Path fromModule = Path.of("../deploy/production/seed-marketplace-test-data.sh");
        Path scriptPath = Files.isRegularFile(fromModule)
                ? fromModule
                : Path.of("deploy/production/seed-marketplace-test-data.sh");
        String script = Files.readString(scriptPath);

        int liveIdentityCheck = script.indexOf("Hostinger running backend source/binary SHA mismatch");
        int imageSelection = script.indexOf("TARGET_IMAGE=\"gp-store-backend:$TARGET_SHA\"");
        int imagePresence = script.indexOf("docker image inspect \"$TARGET_IMAGE\"");
        int tagExport = script.indexOf("export BACKEND_IMAGE_TAG=\"$TARGET_SHA\"");
        int commitExport = script.indexOf("export GIT_COMMIT=\"$TARGET_SHA\"");
        int oneShot = script.indexOf("compose run --rm --no-deps");
        int runtimeCommit = script.indexOf("-e \"GIT_COMMIT=$TARGET_SHA\"");

        assertTrue(liveIdentityCheck >= 0, "the live source/binary identity check was removed");
        assertTrue(imageSelection > liveIdentityCheck,
                "the one-shot image must be selected only after the live identity check");
        assertTrue(imagePresence > imageSelection,
                "the exact deployed image must exist locally; the runner must not rebuild :latest");
        assertTrue(tagExport > imagePresence && commitExport > imagePresence,
                "Compose interpolation must select the target image and runtime commit");
        assertTrue(oneShot > tagExport && oneShot > commitExport,
                "identity must be fixed before the one-shot container starts");
        assertTrue(runtimeCommit > oneShot,
                "the temporary Spring process must receive the full verified GIT_COMMIT");
        assertTrue(script.contains("GPSTORE_TEST_DATA_EXPECTED_SHA=$TARGET_SHA"),
                "the seeder's independent expected-SHA guard must remain enabled");
        assertTrue(script.contains("BATCH_ID=\"MARKETPLACE_TEST_100_SHOPS_V1\""),
                "the exact synthetic batch confirmation must remain enabled");
    }
}
