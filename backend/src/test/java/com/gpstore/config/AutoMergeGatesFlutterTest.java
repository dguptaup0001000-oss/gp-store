package com.gpstore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Auto-merge must wait for code checks and the real release artifacts.
 *
 * WHAT WENT WRONG. Auto-merge waited for two backend jobs. The only job that
 * ran the analyzer and the Flutter tests - build-apk - was on the IGNORE list,
 * put there for a good reason: it also produces three signed release builds,
 * and a missing Play keystore should not block a backend fix.
 *
 * The unintended consequence is that no Dart in this repository was gated by
 * anything. PR #153 merged twenty-seven seconds before its own Flutter failure
 * was reported, and main carried a leaked timer until #154 fixed it.
 *
 * flutter-checks catches Dart failures quickly. build-apk is also required
 * because it builds the final APK/AAB payloads and rejects a native library
 * that is not 16-KB compatible. Both gates have to stay required: a source
 * configuration is not proof of the bytes that will be shipped.
 */
@DisplayName("Auto-merge cannot merge Dart nobody compiled")
class AutoMergeGatesFlutterTest {

    private static final String FLUTTER_JOB = "flutter-checks";
    private static final String RELEASE_JOB = "build-apk";

    private static Path repo(String relative) {
        Path fromModule = Path.of("..", relative);
        return Files.exists(fromModule) ? fromModule : Path.of(relative);
    }

    private static String read(String relative) throws IOException {
        Path path = repo(relative);
        assertTrue(Files.exists(path), "cannot find " + path.toAbsolutePath());
        return Files.readString(path);
    }

    @Test
    @DisplayName("the merge script requires flutter-checks")
    void scriptRequiresFlutterChecks() throws IOException {
        String script = read(".github/scripts/automerge_eligible_pr.py");

        int start = script.indexOf("REQUIRED_CHECK_NAMES");
        assertTrue(start >= 0, "REQUIRED_CHECK_NAMES is gone from the merge script");
        String required = script.substring(start, script.indexOf(')', start));

        assertTrue(required.contains(FLUTTER_JOB),
                "REQUIRED_CHECK_NAMES must include " + FLUTTER_JOB
                        + ", or a failing Flutter test cannot block a merge. Found: " + required);
    }

    @Test
    @DisplayName("flutter-checks is not also on the ignore list")
    void flutterChecksIsNotIgnored() throws IOException {
        String script = read(".github/scripts/automerge_eligible_pr.py");

        int start = script.indexOf("IGNORE_CHECK_NAMES");
        assertTrue(start >= 0, "IGNORE_CHECK_NAMES is gone from the merge script");
        String ignored = script.substring(start, script.indexOf('}', start));

        // Required-and-ignored is not a compile error and not obviously wrong
        // on reading; it just quietly stops gating.
        assertFalse(ignored.contains("\"" + FLUTTER_JOB + "\""),
                FLUTTER_JOB + " is both required and ignored, so it gates nothing");
    }

    @Test
    @DisplayName("the release-artifact job is required and cannot be ignored")
    void releaseArtifactsGateTheMerge() throws IOException {
        String script = read(".github/scripts/automerge_eligible_pr.py");

        int requiredStart = script.indexOf("REQUIRED_CHECK_NAMES");
        String required = script.substring(
                requiredStart, script.indexOf(')', requiredStart));
        assertTrue(required.contains(RELEASE_JOB),
                "the merge gate must wait for final APK/AAB validation");

        int ignoredStart = script.indexOf("IGNORE_CHECK_NAMES");
        String ignored = script.substring(ignoredStart, script.indexOf('}', ignoredStart));
        assertFalse(ignored.contains("\"" + RELEASE_JOB + "\""),
                "the 16-KB release-artifact gate cannot also be ignored");
        assertFalse(script.contains("ci_already_green"),
                "an earlier workflow cannot bypass a missing/pending release build");
    }

    @Test
    @DisplayName("all produced app bundles pass through the 16-KB payload validator")
    void everyAppBundleIsValidated() throws IOException {
        String workflow = read(".github/workflows/build-and-deploy.yml");
        int start = workflow.indexOf("Verify app bundle payloads and 16 KiB ELF alignment");
        assertTrue(start >= 0, "the AAB 16-KB validation step is missing");
        String step = workflow.substring(
                start, workflow.indexOf("\n      - name:", start + 10));

        assertTrue(step.contains("verify_apk_release.py --payload-only"));
        assertTrue(step.contains("gpstore-worker-release.aab"));
        assertTrue(step.contains("gpstore-customer-release.aab"));
        assertTrue(step.contains("gpstore-admin-release.aab"));
        assertTrue(step.contains("gpstore-superadmin-release.aab"));
    }

    @Test
    @DisplayName("NDK r28 and the CameraX native-library workaround agree on 16 KB")
    void androidNativeWorkaroundsDoNotConflict() throws IOException {
        String gradle = read("frontend/android/app/build.gradle");

        assertTrue(gradle.contains("28.2.13676358"), "NDK r28.2 must stay pinned");
        assertTrue(gradle.contains("def camerax = \"1.4.2\""),
                "CameraX 1.4.2+ supplies the compatible image-processing JNI library");
        assertTrue(gradle.contains("force \"androidx.camera:camera-core:$camerax\""),
                "the transitive CameraX 1.3.x library must not win dependency resolution");
        assertTrue(gradle.contains("useLegacyPackaging false"),
                "modern JNI packaging is required for 16-KB ZIP alignment");
        assertFalse(gradle.contains("max-page-size=4096"),
                "a 4-KB linker override would conflict with NDK r28");
    }

    @Test
    @DisplayName("the job exists and actually runs the analyzer and the tests")
    void theJobDoesWhatItsNameClaims() throws IOException {
        String ci = read(".github/workflows/ci.yml");

        assertTrue(ci.contains("  " + FLUTTER_JOB + ":"),
                FLUTTER_JOB + " job is missing from ci.yml, so the required check can never pass");

        // A job that exists but no longer analyses or tests would leave the
        // gate in place and the guarantee gone - the worst of both.
        String job = ci.substring(ci.indexOf("  " + FLUTTER_JOB + ":"));
        int nextJob = job.indexOf("\n  build-and-push-image:");
        if (nextJob > 0) {
            job = job.substring(0, nextJob);
        }
        assertTrue(job.contains("flutter analyze"), FLUTTER_JOB + " no longer runs flutter analyze");
        assertTrue(job.contains("flutter test"), FLUTTER_JOB + " no longer runs flutter test");
    }

    @Test
    @DisplayName("the in-CI auto-merge job waits for it, using index syntax")
    void theAutomergeJobWaitsForIt() throws IOException {
        String ci = read(".github/workflows/ci.yml");
        String automerge = ci.substring(ci.indexOf("  automerge:"));

        assertTrue(automerge.contains(FLUTTER_JOB),
                "the automerge job does not depend on " + FLUTTER_JOB);

        // needs.flutter-checks.result parses the hyphen as SUBTRACTION, so the
        // dotted form evaluates to a number and silently stops gating. This is
        // the same trap that hit needs['pull-fallback'] in offbox-backup.yml.
        assertFalse(automerge.contains("needs." + FLUTTER_JOB + "."),
                "needs." + FLUTTER_JOB + ".result treats the hyphen as subtraction; "
                        + "use needs['" + FLUTTER_JOB + "'].result");
        assertTrue(automerge.contains("needs['" + FLUTTER_JOB + "']"),
                "the automerge condition must reference needs['" + FLUTTER_JOB + "']");
    }

    @Test
    @DisplayName("both Flutter jobs pin the same SDK version")
    void flutterVersionsAgree() throws IOException {
        // flutter-checks duplicates build-apk's setup on purpose (they run in
        // parallel rather than serialising 16 minutes behind 2). Duplication
        // is only acceptable while the two cannot drift: a version skew would
        // mean the gate analysed code under a different Dart than the one that
        // ships it.
        String ci = read(".github/workflows/ci.yml");
        String apk = read(".github/workflows/build-and-deploy.yml");

        String ciVersion = flutterVersion(ci);
        String apkVersion = flutterVersion(apk);

        assertEquals(apkVersion, ciVersion,
                "flutter-checks and build-apk pin different Flutter versions, so the "
                        + "gate does not analyse what the release build compiles");
    }

    private static String flutterVersion(String workflow) {
        int i = workflow.indexOf("flutter-version:");
        assertTrue(i >= 0, "no flutter-version pin found");
        return workflow.substring(i, workflow.indexOf('\n', i)).trim();
    }
}
