package com.gpstore.perf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How long a request is allowed to keep a database connection, expressed as
 * the settings that decide it.
 *
 * WHAT HAPPENED IN PRODUCTION. The pool saturated and requests began failing
 * with 503s. Three log lines, eight seconds apart, are the whole diagnosis:
 *
 *     total=5, active=5, idle=0, waiting=27
 *     total=7, active=7, idle=0, waiting=32
 *     total=9, active=9, idle=0, waiting=27
 *
 * Note what `total` is doing. The pool was GROWING - from five connections
 * towards its ceiling of ten - while thirty requests queued for one. It was
 * not at its limit; it was below its own limit and climbing, because
 * minimum-idle was 2 and the pool had shrunk to that during the preceding
 * quiet period. Every one of those creations is a TCP connect, a TLS
 * handshake and an init-SQL round trip to a database in another data centre,
 * and they were competing with the requests waiting on them.
 *
 * THE FIX IS NOT A BIGGER POOL. maximum-pool-size is unchanged. What changed
 * is that the pool no longer spends the first seconds of every burst below
 * its own ceiling.
 *
 * File-content assertions rather than a running pool, for the same reason as
 * ResourceCeilingTest: every value is env-overridable at run time, so
 * asserting the live configuration would only prove what CI happens to set.
 * What must not silently revert is the decision, and the decision is here.
 */
@DisplayName("A request cannot hold a database connection longer than it needs")
class ConnectionHoldTimeTest {

    private static final Path PROPERTIES = Path.of("src/main/resources/application.properties");

    private String properties() throws IOException {
        assertTrue(Files.exists(PROPERTIES), "application.properties not found - run from backend/");
        return Files.readString(PROPERTIES);
    }

    private int defaultIntOf(String key) throws IOException {
        Matcher m = Pattern.compile(Pattern.quote(key) + "=\\$\\{[A-Z0-9_]+:([^}]*)}")
                .matcher(properties());
        assertTrue(m.find(), key + " is not declared as ${ENV:default}");
        return Integer.parseInt(m.group(1).trim());
    }

    @Test
    @DisplayName("the pool does not have to grow during a burst")
    void thePoolIsFixedSize() throws IOException {
        int max = defaultIntOf("spring.datasource.hikari.maximum-pool-size");
        int minIdle = defaultIntOf("spring.datasource.hikari.minimum-idle");

        assertEquals(max, minIdle,
                "minimum-idle (" + minIdle + ") must equal maximum-pool-size (" + max + "). With a "
                        + "smaller minimum the pool shrinks between bursts and then has to rebuild "
                        + "itself mid-burst - which is exactly what the production log caught it "
                        + "doing: total climbing 5 -> 7 -> 9 while 27 requests waited.");
    }

    @Test
    @DisplayName("making the pool fixed did not quietly raise its ceiling")
    void theCeilingIsUnchanged() throws IOException {
        int max = defaultIntOf("spring.datasource.hikari.maximum-pool-size");

        // The brief was explicit: do not hide the problem by increasing the
        // pool. This is the assertion that the fix above did not do that by
        // the back door. Ten is what production ran at when it saturated, and
        // it stays ten until a measurement - not a hope - justifies more.
        assertTrue(max <= 10,
                "maximum-pool-size is " + max + ". Raising it is not a fix for connections being "
                        + "held too long, and Supabase has its own connection budget that this "
                        + "shares with every other instance.");
    }

    @Test
    @DisplayName("a leaked connection is still reported")
    void leakDetectionSurvives() throws IOException {
        assertTrue(defaultIntOf("spring.datasource.hikari.leak-detection-threshold") > 0,
                "Leak detection is off. On a pool of ten, one leaked connection is ten percent of "
                        + "the shop's capacity gone with nothing in the log saying so - and it is "
                        + "the first thing to rule out when the pool saturates.");
    }

    @Test
    @DisplayName("connections are retired before Supabase can close them underneath us")
    void connectionsAreRetiredOnOurOwnTerms() throws IOException {
        int maxLifetime = defaultIntOf("spring.datasource.hikari.max-lifetime");
        int keepalive = defaultIntOf("spring.datasource.hikari.keepalive-time");

        // This is what makes a FIXED pool safe. Holding ten connections open
        // permanently only works if this app retires them on its own schedule;
        // otherwise the pooler closes an idle one, the pool hands it out
        // anyway, and the request dies on a closed connection.
        assertTrue(keepalive < maxLifetime,
                "keepalive-time must be shorter than max-lifetime, or connections are never probed "
                        + "before they are retired.");
        assertTrue(maxLifetime <= 30 * 60 * 1000,
                "max-lifetime is longer than Supabase's own idle timeout is likely to be, which is "
                        + "how a pool ends up holding connections the server has already closed.");
    }
}
