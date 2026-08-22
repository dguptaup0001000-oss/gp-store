package com.gpstore.search;

import com.gpstore.entity.SearchSynonym;
import com.gpstore.repository.SearchSynonymRepository;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.io.ClassPathResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The Hindi/Hinglish vocabulary, indexed by phonetic key and held in memory.
 *
 * <p>WHY IN MEMORY. This is consulted on every keystroke of every search, and
 * it is a few hundred short rows - a database round trip per token would be
 * the slowest part of a search that is supposed to feel instant. Held in an
 * immutable map behind an AtomicReference so a refresh swaps the whole map at
 * once and readers never see a half-built one, without any locking on the
 * read path.
 *
 * <p>WHY KEYED BY PHONETIC KEY. Storing "chini" and looking it up literally
 * would miss "cheeni", "chinee" and "chiny". Indexing by the phonetic key
 * means the table stores one spelling and matches all of them - which is what
 * keeps it roughly one row per concept.
 *
 * <p>A collision (two different words sharing a key) resolves to whichever
 * row loads first, and is logged. The alternative - returning both - would
 * widen every search that touched an ambiguous word, which costs more than
 * the rare wrong-but-plausible expansion.
 */
@Component
public class SynonymDictionary {

    private static final Logger log = LoggerFactory.getLogger(SynonymDictionary.class);

    /** How often to pick up rows added by an admin. */
    private static final long REFRESH_MS = 10 * 60 * 1000L;

    private static final String VOCABULARY_RESOURCE = "search-synonyms.csv";

    /**
     * Ceiling for the distance comparison between colliding candidates.
     * areCloseEnough has already decided acceptability; this only needs to be
     * large enough to rank the survivors against each other.
     */
    private static final int MAX_CONFIRM_DISTANCE = 6;

    private final SearchSynonymRepository repository;

    /**
     * Phonetic key -> the row that produced it. The stored TERM is kept
     * alongside the canonical word because the key alone is not enough to
     * confirm a match - see canonicalFor.
     */
    private record Entry(String term, String canonical) {
    }

    /**
     * Phonetic key -> EVERY row that produced it.
     *
     * <p>A list, not a single entry, and that is a bug fix rather than a
     * generalisation. The key is deliberately lossy: "chana" and "chini" both
     * reduce to "cn". Keeping only the first row loaded meant the second word
     * was not merely unavailable - it resolved to the FIRST one's meaning,
     * because the edit-distance confirmation was generous enough to accept
     * it. A customer asking for chana got sugar.
     *
     * <p>The key finds candidates; the distance decides between them.
     */
    private final AtomicReference<Map<String, List<Entry>>> byPhoneticKey =
            new AtomicReference<>(Map.of());

    public SynonymDictionary(SearchSynonymRepository repository) {
        this.repository = repository;
        seedMissing();
        refresh();
    }

    /**
     * Loads the bundled vocabulary the first time this runs against a
     * database that has none.
     *
     * <p>WHY THE APPLICATION SEEDS THIS RATHER THAN A MIGRATION. Seeding from
     * V12 would tie Smart Search to migrations having been run. CI builds its
     * schema from the JPA entities with Flyway disabled, so the table would
     * exist and be empty there - search would quietly lose every Hindi word,
     * and nothing would fail to say so. The same is true of any fresh
     * developer database. Seeding here covers every environment from one
     * source of truth.
     *
     * <p>ONLY EVER INSERTS TERMS THAT ARE ABSENT, so a shop's own edits and
     * additions are never overwritten on restart - while a word added to the
     * bundled file still reaches a database that was seeded long ago.
     */
    private void seedMissing() {
        try {
            List<SearchSynonym> defaults = readBundledVocabulary();
            if (defaults.isEmpty()) return;

            // ADDS WHAT IS MISSING, rather than only filling an empty table.
            //
            // The original guard was `if (count() > 0) return`, which was
            // correct for the day it was written and quietly wrong for every
            // day after: once a shop's database had been seeded, a word added
            // to the bundled file could NEVER reach it. The vocabulary would
            // ship, deploy, and silently do nothing - the failure mode being
            // that search simply stayed as dumb as it was, with nothing in
            // any log to say why.
            //
            // The guarantee that mattered is kept exactly. Existing rows are
            // never read, never updated and never deleted, so a shop's own
            // edits and its corrections to these defaults still survive every
            // restart. Only terms that are absent are inserted.
            Set<String> existing = new HashSet<>();
            for (SearchSynonym row : repository.findAll()) {
                if (row.getTerm() != null) existing.add(row.getTerm().toLowerCase(Locale.ROOT));
            }

            List<SearchSynonym> missing = new ArrayList<>();
            for (SearchSynonym candidate : defaults) {
                if (existing.add(candidate.getTerm().toLowerCase(Locale.ROOT))) {
                    missing.add(candidate);
                }
            }

            if (missing.isEmpty()) return;

            repository.saveAll(missing);
            log.info("Added {} search synonyms from the bundled vocabulary", missing.size());
        } catch (Exception e) {
            // Two instances starting at once can both see the same term
            // missing and both insert it; the unique constraint on term means
            // the loser fails here, having lost a race whose winner did the
            // work. That is not an error worth failing startup over - and
            // neither is any other seeding problem, since search still runs
            // without the dictionary.
            log.warn("Could not seed the search vocabulary (it may already have been seeded): {}", e.getMessage());
        }
    }

    private List<SearchSynonym> readBundledVocabulary() {
        List<SearchSynonym> loaded = new ArrayList<>();

        try (InputStream stream = new ClassPathResource(VOCABULARY_RESOURCE).getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                int comma = trimmed.indexOf(',');
                if (comma <= 0 || comma == trimmed.length() - 1) {
                    log.warn("Skipping malformed vocabulary line: {}", trimmed);
                    continue;
                }

                SearchSynonym synonym = new SearchSynonym();
                synonym.setTerm(trimmed.substring(0, comma).trim());
                synonym.setCanonicalTerm(trimmed.substring(comma + 1).trim());
                synonym.setActive(true);
                loaded.add(synonym);
            }
        } catch (Exception e) {
            log.warn("Could not read the bundled search vocabulary: {}", e.getMessage());
        }

        return loaded;
    }

    /**
     * The English term to search for instead of {@code token}, if this is a
     * word the dictionary knows.
     *
     * <p>Empty means "no opinion" - the caller should search the token as
     * typed, which is the right behaviour for every English product name.
     */
    public Optional<String> canonicalFor(String token) {
        String key = SearchNormalizer.phoneticKey(token);
        if (key.isEmpty()) return Optional.empty();

        List<Entry> candidates = byPhoneticKey.get().get(key);
        if (candidates == null || candidates.isEmpty()) return Optional.empty();

        // The key is deliberately lossy, so it produces false friends:
        // "britannia" and "bartan" both reduce to "brtn". Confirming with an
        // edit distance against the stored term is what stops a real brand
        // name being translated into dishwash - the key finds candidates
        // cheaply, this decides whether they are actually the same word.
        //
        // The CLOSEST candidate wins, not the first. With one entry per key
        // this changes nothing; where two real words collide it is the whole
        // difference between chana meaning chickpea and chana meaning sugar.
        Entry best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Entry candidate : candidates) {
            if (!SearchNormalizer.areCloseEnough(token, candidate.term())) continue;
            int distance = SearchNormalizer.editDistance(token, candidate.term(), MAX_CONFIRM_DISTANCE);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        if (best == null) return Optional.empty();

        String canonical = best.canonical();

        // A word that maps to itself ("ghee" -> "ghee", "masala" -> "masala")
        // is in the table so that its spellings unify, not to be substituted.
        // Reporting it as a correction would show the customer a "did you
        // mean" for a word they spelled correctly.
        if (canonical.equalsIgnoreCase(token)) return Optional.empty();

        return Optional.of(canonical);
    }

    @Scheduled(fixedDelay = REFRESH_MS)
    public final void refresh() {
        try {
            Map<String, List<Entry>> rebuilt = new HashMap<>();
            int rows = 0;
            for (SearchSynonym synonym : repository.findByActiveTrue()) {
                String key = SearchNormalizer.phoneticKey(synonym.getTerm());
                if (key.isEmpty()) continue;

                // KEPT, not discarded. A colliding row used to be dropped and
                // logged, which is why "chana" resolved to sugar: the row that
                // would have said chickpea was never in the map at all.
                rebuilt.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new Entry(synonym.getTerm(), synonym.getCanonicalTerm()));
                rows++;
            }

            Map<String, List<Entry>> frozen = new HashMap<>(rebuilt.size());
            rebuilt.forEach((key, entries) -> frozen.put(key, List.copyOf(entries)));

            byPhoneticKey.set(Map.copyOf(frozen));
            log.debug("Loaded {} search synonyms across {} phonetic keys", rows, frozen.size());
        } catch (Exception e) {
            // Search must keep working without the dictionary - it simply
            // loses Hindi/Hinglish translation and falls back to the
            // phonetic and trigram layers, which need no data at all.
            log.warn("Could not load search synonyms; Smart Search continues without translation: {}", e.getMessage());
        }
    }

    /**
     * How many phonetic KEYS are loaded. Fewer than the number of rows
     * whenever two words sound alike - both are kept, and canonicalFor picks
     * between them by edit distance.
     */
    public int size() {
        return byPhoneticKey.get().size();
    }
}
