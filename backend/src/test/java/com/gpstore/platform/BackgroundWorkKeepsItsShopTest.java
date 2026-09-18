package com.gpstore.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Work that moves to another thread takes its shop with it, or says it does not.
 *
 * <p>THE FAILURE MODE THIS EXISTS FOR. {@code TenantContext} is a ThreadLocal.
 * Hand a lambda to an executor and the continuation runs on a pool thread with
 * NO scope - which does not fail loudly, it silently stops narrowing. A read
 * that was one shop's becomes every shop's; an insert of a shop-owned row picks
 * no shop at all. Nothing in a log says a boundary moved, because from the
 * database's point of view none did: the filter simply was not there.
 *
 * <p>So a scoped operation can become platform-global purely by changing where
 * it runs, and the diff that does it looks like a performance improvement.
 *
 * <p>THE RULE: every hand-off to another thread goes through
 * {@link BackgroundWorkScope}, which either carries the request's scope or
 * declares {@code TenantScope.platform()} on purpose. "Unscoped because nobody
 * thought about it" and "platform because this genuinely spans shops" are
 * indistinguishable at runtime and must not be indistinguishable in the source.
 *
 * <p>A SOURCE CHECK RATHER THAN A BEHAVIOURAL ONE, deliberately. Asserting that
 * some particular background job kept its shop proves that job; it says nothing
 * about the one somebody adds next week, which is where this class of bug
 * actually comes from.
 */
@DisplayName("Background work keeps its shop")
class BackgroundWorkKeepsItsShopTest {

    /**
     * A hand-off to another thread: {@code submit(} or {@code execute(} on
     * anything whose name ends in Executor, plus Spring's annotation.
     */
    private static final Pattern HAND_OFF =
            Pattern.compile("(?i)\\b\\w*executor\\s*\\.\\s*(submit|execute)\\s*\\(|@Async\\b");

    /** The wrapper that makes the scope explicit, in either direction. */
    private static final Pattern CARRIES_SCOPE =
            Pattern.compile("BackgroundWorkScope\\s*\\.\\s*(carrying|runAs)");

    @Test
    @DisplayName("nothing hands work to a thread without saying what shop it is for")
    void everyHandOffDeclaresItsScope() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path file : javaSources()) {
            // THE ONE FILE ALLOWED TO KNOW: BackgroundWorkScope is the wrapper
            // itself, and AfterCommitExecutor is the shared hand-off that
            // already captures the request's scope and wraps every task in it.
            String name = file.getFileName().toString();
            if (name.equals("BackgroundWorkScope.java") || name.equals("AfterCommitExecutor.java")) {
                continue;
            }

            String source = Files.readString(file);
            if (!HAND_OFF.matcher(source).find()) {
                continue;
            }
            if (!CARRIES_SCOPE.matcher(source).find()) {
                offenders.add(file.toString());
            }
        }

        assertTrue(offenders.isEmpty(),
                "These hand work to another thread without declaring a tenant scope, so it "
                        + "runs with none - and a shop-scoped operation quietly becomes "
                        + "platform-global. Wrap the task in BackgroundWorkScope.carrying("
                        + "TenantContext.current(), ...) to keep the request's shop, or "
                        + "carrying(TenantScope.platform(), ...) to say it genuinely spans "
                        + "shops:\n" + String.join("\n", offenders));
    }

    @Test
    @DisplayName("the shared after-commit hand-off captures the request's scope")
    void afterCommitCarriesTheRequestsShop() throws IOException {
        // The file exempted above is exempted because of what it does, so what
        // it does is asserted rather than assumed.
        String source = Files.readString(
                Path.of("src/main/java/com/gpstore/config/AfterCommitExecutor.java"));

        assertTrue(source.contains("TenantContext.current()"),
                "AfterCommitExecutor must CAPTURE the scope on the request thread");
        assertTrue(CARRIES_SCOPE.matcher(source).find(),
                "and carry it into the task it submits");
        assertFalse(source.contains("TenantScope.platform()"),
                "and it must not widen an order's side effects to the platform - "
                        + "sending the notification for an order belongs to that order's shop");
    }

    @Test
    @DisplayName("the guard can actually see a bare hand-off")
    void theGuardCanSeeABareHandOff() {
        // A GUARD ON THE GUARD. The walk above passes trivially if the pattern
        // stops matching, and a test that cannot fail is worse than no test.
        assertTrue(HAND_OFF.matcher("orderSideEffectsExecutor.submit(() -> work());").find());
        assertTrue(HAND_OFF.matcher("    executor.execute(task);").find());
        assertTrue(HAND_OFF.matcher("  @Async").find());
        assertFalse(HAND_OFF.matcher("int submitted = 0;").find());

        assertTrue(CARRIES_SCOPE.matcher(
                "BackgroundWorkScope.carrying(TenantScope.platform(), task)").find());
        assertFalse(CARRIES_SCOPE.matcher("executor.submit(task)").find());
    }

    @Test
    @DisplayName("the walk actually reaches the source tree")
    void theWalkReachesSomething() throws IOException {
        List<Path> sources = javaSources();
        assertTrue(sources.size() > 300,
                "a walk that found " + sources.size() + " files is not walking this "
                        + "repository, and the check above would pass by seeing nothing");
        assertEquals(1, sources.stream()
                        .filter(p -> p.getFileName().toString().equals("NotificationService.java"))
                        .count(),
                "the file that carried the one bare submit must be in scope of the walk");
    }

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> walk = Files.walk(Path.of("src/main/java"))) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}
