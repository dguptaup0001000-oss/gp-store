package com.gpstore.perf;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

/**
 * Counts the SQL statements Hibernate actually issues while a block of code
 * runs, and how long that block took.
 *
 * This exists because "this endpoint feels slow" and "this endpoint issues 40
 * queries" are different claims, and only the second one can be fixed with
 * confidence. An N+1 is invisible in a small test database - ten cart items
 * respond fast enough that nothing looks wrong - and only shows up as a
 * count. Counting makes the regression detectable before it reaches
 * production, where the same code path meets real row counts and real network
 * latency per round trip.
 *
 * Timing here measures APPLICATION time against a local database. It is
 * deliberately NOT presented as production latency: a local Postgres over a
 * unix socket has none of the per-query network cost that a managed database
 * across a network does, which is exactly why query COUNT matters more than
 * the millisecond figure. Cutting 40 queries to 4 saves 36 round trips, and
 * on a remote database each round trip is worth far more than it is here.
 */
public final class QueryCounter {

    private QueryCounter() {
    }

    public record Result(long queryCount, long millis) {
        @Override
        public String toString() {
            return queryCount + " queries in " + millis + " ms";
        }
    }

    /**
     * Runs {@code work} and reports the SQL it caused.
     *
     * The statistics are reset immediately before the block rather than
     * relying on a fresh counter, so anything a previous test left behind
     * cannot be attributed here. Hibernate statistics must be enabled for
     * this to report anything - see application.properties in the test
     * profile.
     */
    public static Result measure(EntityManagerFactory entityManagerFactory, Runnable work) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        long start = System.nanoTime();
        work.run();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        return new Result(statistics.getPrepareStatementCount(), elapsedMillis);
    }

    /**
     * Same, but for work that returns a value - so a test can assert on both
     * the result and the cost of producing it.
     */
    public static <T> T measureInto(EntityManagerFactory entityManagerFactory,
                                    java.util.function.Consumer<Result> report,
                                    java.util.function.Supplier<T> work) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        long start = System.nanoTime();
        T value = work.get();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        report.accept(new Result(statistics.getPrepareStatementCount(), elapsedMillis));
        return value;
    }

    /** Clears any pending persistence-context state so it cannot mask a query. */
    public static void clear(EntityManager entityManager) {
        entityManager.flush();
        entityManager.clear();
    }
}
