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
 * A guard on the settings that were the difference between the container
 * living and being killed.
 *
 * WHAT HAPPENED. The load test did not fail because a query was slow. The
 * container was measured at 666 MB resident on a 512 MB limit: metaspace had
 * reserved 1179 MB with no ceiling, the code cache 240 MB with no ceiling, and
 * the Dockerfile passed the JVM no memory arguments at all - so the heap took
 * the default 25% of the limit and every non-heap region took whatever it
 * liked. Render kills a container over its limit, its proxy then has nothing
 * to talk to, and that is what a 502 is. The two memory dips on the graph are
 * those restarts.
 *
 * NONE OF IT SHOWS UP IN A HEAP METRIC, which is why it went unnoticed for so
 * long and why this test reads the files rather than the running JVM: the
 * regions that overran are exactly the ones a heap gauge does not include.
 *
 * These are file-content assertions on purpose. Every value below is
 * env-overridable at run time, so asserting the RUNNING configuration would
 * only prove what CI happens to set. What must not silently disappear is the
 * ceiling itself, and that lives in the file.
 */
@DisplayName("Memory and connection ceilings survive")
class ResourceCeilingTest {

    private static final Path DOCKERFILE = Path.of("Dockerfile");
    private static final Path PROPERTIES = Path.of("src/main/resources/application.properties");

    private String dockerfile() throws IOException {
        assertTrue(Files.exists(DOCKERFILE), "Dockerfile not found - run from the backend module");
        return Files.readString(DOCKERFILE);
    }

    private String properties() throws IOException {
        assertTrue(Files.exists(PROPERTIES), "application.properties not found");
        return Files.readString(PROPERTIES);
    }

    /**
     * The default value inside ${NAME:default}, so a test can check what
     * production gets when nobody sets the environment variable - which is
     * the case that produced the outage.
     */
    private String defaultOf(String text, String key) {
        Matcher m = Pattern.compile(Pattern.quote(key) + "=\\$\\{[A-Z0-9_]+:([^}]*)}").matcher(text);
        assertTrue(m.find(), key + " is not declared as ${ENV:default} in application.properties");
        return m.group(1);
    }

    // ------------------------------------------------------------ the JVM

    @Test
    @DisplayName("every unbounded memory region the container overran now has a ceiling")
    void jvmRegionsAreBounded() throws IOException {
        String docker = dockerfile();

        // The heap. Left alone it is 25% of the limit, which sounds safe and
        // is the reason nobody looked: the heap was never the problem, the
        // 75% nobody was accounting for was.
        assertTrue(docker.contains("MaxRAMPercentage"),
                "No MaxRAMPercentage: the heap is back to the default 25% and the rest is unaccounted for");

        // The two that actually overran.
        assertTrue(docker.contains("MaxMetaspaceSize"),
                "No MaxMetaspaceSize: metaspace reserved 1179 MB on a 512 MB container");
        assertTrue(docker.contains("ReservedCodeCacheSize"),
                "No ReservedCodeCacheSize: the code cache reserved 240 MB with no ceiling");

        // Direct buffers are invisible to every heap metric and are exactly
        // what a Netty-based Redis client and a NIO connector allocate.
        assertTrue(docker.contains("MaxDirectMemorySize"),
                "No MaxDirectMemorySize: off-heap buffers are uncapped and unmeasured");

        // Forty worker threads at the default 1 MB stack is 40 MB of address
        // space that nothing reports either.
        assertTrue(docker.contains("ThreadStackSize") || docker.contains("-Xss"),
                "No thread stack size: 40 Tomcat threads at the default 1 MB is 40 MB unaccounted for");
    }

    @Test
    @DisplayName("glibc is stopped from inflating RSS with per-thread arenas")
    void mallocArenasAreCapped() throws IOException {
        // Not a JVM setting and not visible from inside the JVM at all.
        // glibc gives each thread its own arena, and RSS - the number Render
        // kills on - counts every one of them.
        assertTrue(dockerfile().contains("MALLOC_ARENA_MAX"),
                "MALLOC_ARENA_MAX is unset: glibc arenas inflate RSS, which is the number that gets killed");
    }

    @Test
    @DisplayName("running out of memory kills the JVM instead of leaving it thrashing")
    void outOfMemoryIsFatal() throws IOException {
        // A JVM that catches OutOfMemoryError and limps on serves errors
        // while looking alive to a health check. Dying is the honest
        // behaviour: the platform restarts it and the restart is visible.
        assertTrue(dockerfile().contains("ExitOnOutOfMemoryError")
                        || dockerfile().contains("CrashOnOutOfMemoryError"),
                "Nothing makes an OutOfMemoryError fatal - the container would stay up and stay broken");
    }

    @Test
    @DisplayName("the JVM sizes its pools from the container's share, not the host's cores")
    void processorCountMatchesTheContainerShare() throws IOException {
        // 0.5 vCPU on a host reporting many cores means the JVM builds a GC
        // thread pool, a ForkJoinPool and a JIT compiler pool for a machine
        // it does not have. Each one costs memory and none of it helps.
        assertTrue(dockerfile().contains("ActiveProcessorCount"),
                "No ActiveProcessorCount: the JVM sizes its thread pools from the host, not the 0.5 vCPU share");
    }

    // --------------------------------------------------- the request path

    @Test
    @DisplayName("a request never waits half a minute for a database connection")
    void poolAcquisitionFailsFast() throws IOException {
        String timeout = defaultOf(properties(), "spring.datasource.hikari.connection-timeout");
        int ms = Integer.parseInt(timeout.trim());

        // The load test's p95 was 21.6 s and its maximum a full minute, on a
        // 30 s acquisition timeout. A request queueing thirty seconds for one
        // of ten connections is not being served, it is being stored: the
        // customer has gone, and the thread is still held.
        assertTrue(ms <= 10_000,
                "Hikari connection-timeout is " + ms + " ms. Above ~10 s the pool hoards doomed requests "
                        + "instead of shedding them, which is what produced the 21.6 s p95.");
        assertTrue(ms >= 1_000,
                "Hikari connection-timeout is " + ms + " ms, which is too aggressive - a normal "
                        + "checkout under a brief burst would fail rather than wait its turn.");
    }

    @Test
    @DisplayName("a leaked connection is reported rather than merely missed")
    void connectionLeaksAreDetected() throws IOException {
        String value = defaultOf(properties(), "spring.datasource.hikari.leak-detection-threshold");
        assertTrue(Integer.parseInt(value.trim()) > 0,
                "Leak detection is off. On a pool of ten, one leaked connection is ten percent of "
                        + "the shop's capacity gone with nothing in the log saying so.");
    }

    @Test
    @DisplayName("Tomcat will not accept thousands of connections it cannot serve")
    void acceptedConnectionsAreBounded() throws IOException {
        String text = properties();
        int maxConnections = Integer.parseInt(defaultOf(text, "server.tomcat.max-connections").trim());
        int maxThreads = Integer.parseInt(defaultOf(text, "server.tomcat.threads.max").trim());

        assertTrue(maxConnections < 8192,
                "max-connections is at or above Tomcat's default of 8192, which on a 512 MB container "
                        + "is a promise to accept connections there is no memory to hold.");
        assertTrue(maxConnections > maxThreads,
                "max-connections (" + maxConnections + ") must exceed threads.max (" + maxThreads
                        + "), or keep-alive would make every idle client hold a slot another needs.");
    }

    @Test
    @DisplayName("a half-sent request cannot hold a worker thread indefinitely")
    void slowRequestsAreTimedOut() throws IOException {
        String text = properties();
        int connectionTimeout = Integer.parseInt(defaultOf(text, "server.tomcat.connection-timeout").trim());
        assertTrue(connectionTimeout > 0 && connectionTimeout <= 20_000,
                "Tomcat connection-timeout is " + connectionTimeout + " ms. There are only forty worker "
                        + "threads; a request whose headers never arrive must not own one for longer.");
    }

    // --------------------------------------------------- the JPA session

    @Test
    @DisplayName("a database connection is held for the query, not for the whole request")
    void openSessionInViewIsOff() throws IOException {
        String value = defaultOf(properties(), "spring.jpa.open-in-view");
        assertEquals("false", value.trim(),
                "open-in-view is on. It holds a pooled connection from the filter chain until the "
                        + "response has been serialised - so on a pool of ten, threads wait on each "
                        + "other rather than on the database. That is the shape of the 21.6 s p95.");
    }
}
