package com.gpstore.platform;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every route the Flutter apps call still exists on this backend.
 *
 * WHY A JAVA TEST READS DART. There is no Flutter SDK in the build container
 * that runs this suite, and the CI job that does have one runs separately - so
 * a backend change that renames or removes a route the app depends on can go
 * green here and be found by a customer. The two halves of this product are in
 * one repository precisely so that does not have to happen.
 *
 * This does not compile Dart and does not claim to. It reads the paths the
 * repositories ask for and checks each against the controllers in this module.
 * That catches the specific failure worth catching: a route renamed on one
 * side and not the other. It does not catch a changed field type, a changed
 * status code, or anything about how a screen behaves.
 *
 * SKIPS RATHER THAN FAILS WHEN THE FRONTEND IS ABSENT. The backend is built on
 * its own in some pipelines, and a test that failed because a sibling
 * directory was not checked out would be noise rather than a finding.
 */
@DisplayName("The Flutter apps only call routes this backend serves")
class TheAppAndTheApiAgreeTest {

    /**
     * Paths the app builds at runtime rather than writing out, which this
     * crude reader cannot resolve.
     *
     * EMPTY, and worth keeping that way. Every entry is a call nothing
     * verifies.
     */
    private static final Set<String> NOT_STATICALLY_RESOLVABLE = Set.of();

    @Test
    @DisplayName("every API path in the Dart repositories matches a controller route")
    void theAppCallsNothingThatIsNotThere() throws IOException {
        Path frontend = Path.of("..", "frontend", "lib");
        Assumptions.assumeTrue(Files.isDirectory(frontend),
                "frontend/ is not checked out beside backend/ - nothing to compare");

        Set<Route> routes = controllerRoutes();
        assertFalse(routes.isEmpty(), "no controller routes found - the scan is wrong");

        List<String> unmatched = new ArrayList<>();
        int checked = 0;

        try (Stream<Path> dartFiles = Files.walk(frontend)) {
            for (Path file : dartFiles.filter(p -> p.toString().endsWith(".dart")).toList()) {
                String source = Files.readString(file);
                Matcher call = Pattern
                        .compile("dio\\.(get|post|put|delete|patch)\\(\\s*'([^']+)'")
                        .matcher(source);
                while (call.find()) {
                    String method = call.group(1).toUpperCase(Locale.ROOT);
                    String path = normalise(call.group(2));
                    if (path == null || NOT_STATICALLY_RESOLVABLE.contains(path)) {
                        continue;
                    }
                    checked++;
                    if (routes.stream().noneMatch(route -> route.matches(method, path))) {
                        unmatched.add(method + " " + path
                                + "  (" + file.getFileName() + ")");
                    }
                }
            }
        }

        assertTrue(checked > 0, "no API calls found in the Dart sources - the reader is wrong");
        assertTrue(unmatched.isEmpty(),
                "the app calls " + unmatched.size() + " route(s) this backend does not serve. "
                        + "Either the route was renamed and the app was not updated, or the app "
                        + "was written against an endpoint that does not exist. Both ship a "
                        + "screen that fails for every customer who opens it:\n  "
                        + String.join("\n  ", unmatched));
    }

    /** A path with its interpolations replaced by a wildcard segment. */
    private static String normalise(String raw) {
        String path = raw.replaceAll("\\$\\{[^}]+\\}", "*").replaceAll("\\$\\w+", "*");
        return path.startsWith("/api/") ? path : null;
    }

    private record Route(String method, String pattern) {
        boolean matches(String otherMethod, String path) {
            if (!method.equals(otherMethod)) {
                return false;
            }
            String[] mine = pattern.split("/", -1);
            String[] theirs = path.split("/", -1);
            if (mine.length != theirs.length) {
                return false;
            }
            for (int i = 0; i < mine.length; i++) {
                boolean myWildcard = mine[i].startsWith("{") && mine[i].endsWith("}");
                if (myWildcard || "*".equals(theirs[i])) {
                    continue;
                }
                if (!mine[i].equals(theirs[i])) {
                    return false;
                }
            }
            return true;
        }
    }

    private static Set<Route> controllerRoutes() throws IOException {
        Set<Route> routes = new LinkedHashSet<>();
        Path main = Path.of("src", "main", "java");
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (!source.contains("@RestController") && !source.contains("@Controller")) {
                    continue;
                }
                Matcher base = Pattern.compile("@RequestMapping\\(\\s*\"([^\"]*)\"").matcher(source);
                String prefix = base.find() ? base.group(1) : "";

                Matcher mapped = Pattern
                        .compile("@(Get|Post|Put|Delete|Patch)Mapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"")
                        .matcher(source);
                while (mapped.find()) {
                    routes.add(new Route(mapped.group(1).toUpperCase(Locale.ROOT),
                            prefix + mapped.group(2)));
                }
                Matcher bare = Pattern
                        .compile("@(Get|Post|Put|Delete|Patch)Mapping(?![\\(\\w])")
                        .matcher(source);
                while (bare.find()) {
                    routes.add(new Route(bare.group(1).toUpperCase(Locale.ROOT),
                            prefix.isEmpty() ? "/" : prefix));
                }
            }
        }
        return routes;
    }
}
