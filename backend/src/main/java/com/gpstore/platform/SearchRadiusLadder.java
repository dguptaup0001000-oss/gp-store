package com.gpstore.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * How far "search farther" looks, one tap at a time — and it is configuration.
 *
 * <p>WHY THIS STOPPED BEING A CONSTANT. The rungs used to be a
 * {@code static final List} in {@link ShopDiscovery}: 3, 5, 10, 15, 25 km.
 * That is a reasonable ladder for one town and a bad one for two others.
 * Part 2 §6 gives the shape of the problem exactly:
 *
 * <pre>
 *   a small town:   8 → 20 → 50 → 100 → 500 km
 *   a dense city:   3 →  8 → 15 →  30 → 100 km
 * </pre>
 *
 * <p>A marketplace serving a district where the next hardware shop is forty
 * kilometres away and one serving a city where four kiranas share a street
 * cannot use the same ladder, and neither of them is wrong. So the rungs are
 * {@code marketplace.search.radii-km} and the default is the ladder this
 * deployment has always run on — a deployment that says nothing keeps exactly
 * the behaviour it had.
 *
 * <p>THE APP STILL DOES NOT CHOOSE. Making the ladder configurable is not the
 * same as letting a client ask for an arbitrary radius: two clients would
 * then disagree about what "farther" means, and one asking for 500 km would
 * turn a local marketplace into a national one by accident. The ladder is the
 * server's, the deployment's operator sets it, and a client value can only
 * ever be clamped down to {@link #max()}.
 *
 * <p>LOCAL-FIRST IS NOT LOCAL-ONLY (§6). The top rung exists because a rare
 * spare part legitimately is forty kilometres away, and an empty screen is a
 * worse answer than a far one that says how far it is.
 */
@Component
public class SearchRadiusLadder {

    /**
     * The ladder a deployment gets for saying nothing.
     *
     * <p>Deliberately the exact list that used to be hard-coded, so that
     * making this configurable changes no running deployment's behaviour.
     */
    static final String DEFAULT_RUNGS = "3,5,10,15,25";

    private final List<BigDecimal> rungs;

    public SearchRadiusLadder(
            @Value("${marketplace.search.radii-km:}") String configured) {
        this.rungs = parse(configured);
    }

    /**
     * Reads the property, and falls back rather than throwing.
     *
     * <p>A MALFORMED LADDER MUST NOT STOP THE MARKETPLACE BOOTING. A typo in
     * one environment variable taking the whole shop offline is a far worse
     * failure than running on the default rungs, and the default rungs are a
     * working marketplace. Anything unparseable, out of order, non-positive
     * or empty falls back whole - half a ladder is not better than the
     * default one, it is just a ladder nobody chose.
     */
    private static List<BigDecimal> parse(String configured) {
        if (configured == null || configured.isBlank()) {
            return parseOrEmpty(DEFAULT_RUNGS);
        }
        List<BigDecimal> parsed = parseOrEmpty(configured);
        return parsed.isEmpty() ? parseOrEmpty(DEFAULT_RUNGS) : parsed;
    }

    private static List<BigDecimal> parseOrEmpty(String raw) {
        List<BigDecimal> out = new ArrayList<>();
        BigDecimal previous = null;
        for (String piece : raw.split(",")) {
            String trimmed = piece.trim().toLowerCase(Locale.ROOT).replace("km", "").trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            BigDecimal rung;
            try {
                rung = new BigDecimal(trimmed);
            } catch (NumberFormatException notANumber) {
                return List.of();
            }
            // Ascending and positive, or it is not a ladder: a rung that goes
            // backwards would make "search farther" search nearer, and the
            // button would stop doing what its label says.
            if (rung.signum() <= 0 || (previous != null && rung.compareTo(previous) <= 0)) {
                return List.of();
            }
            out.add(rung);
            previous = rung;
        }
        return List.copyOf(out);
    }

    /** The rungs, nearest first. Never empty. */
    public List<BigDecimal> rungs() {
        return rungs;
    }

    /** The first rung - where "there is nothing near me" starts looking. */
    public BigDecimal first() {
        return rungs.get(0);
    }

    /** The widest a customer may look. Beyond this, "local" has stopped meaning anything. */
    public BigDecimal max() {
        return rungs.get(rungs.size() - 1);
    }

    /**
     * The next rung above {@code radiusKm}, or empty at the top.
     *
     * <p>Null means "nothing has been searched yet", whose next step is the
     * first rung - so the app can label its button without knowing the ladder.
     */
    public Optional<BigDecimal> next(BigDecimal radiusKm) {
        for (BigDecimal rung : rungs) {
            if (radiusKm == null || rung.compareTo(radiusKm) > 0) {
                return Optional.of(rung);
            }
        }
        return Optional.empty();
    }

    /** Clamps a client-supplied radius down. It can narrow, never widen (§78). */
    public BigDecimal clamp(BigDecimal radiusKm) {
        return radiusKm == null ? null : radiusKm.min(max());
    }
}
