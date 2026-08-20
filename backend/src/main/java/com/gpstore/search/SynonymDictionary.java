package com.gpstore.search;

import com.gpstore.entity.SearchSynonym;
import com.gpstore.repository.SearchSynonymRepository;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private final SearchSynonymRepository repository;

    /**
     * Phonetic key -> the row that produced it. The stored TERM is kept
     * alongside the canonical word because the key alone is not enough to
     * confirm a match - see canonicalFor.
     */
    private record Entry(String term, String canonical) {
    }

    private final AtomicReference<Map<String, Entry>> byPhoneticKey =
            new AtomicReference<>(Map.of());

    public SynonymDictionary(SearchSynonymRepository repository) {
        this.repository = repository;
        seedIfEmpty();
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
     * <p>ONLY EVER SEEDS AN EMPTY TABLE, so a shop's own edits and additions
     * are never overwritten on restart.
     */
    private void seedIfEmpty() {
        try {
            if (repository.count() > 0) return;

            List<SearchSynonym> defaults = readBundledVocabulary();
            if (defaults.isEmpty()) return;

            repository.saveAll(defaults);
            log.info("Seeded {} search synonyms from the bundled vocabulary", defaults.size());
        } catch (Exception e) {
            // Two instances starting at once can both see an empty table and
            // both insert; the unique constraint on term means the loser
            // fails here, having lost a race whose winner did the work. That
            // is not an error worth failing startup over - and neither is any
            // other seeding problem, since search still runs without the
            // dictionary.
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

        Entry entry = byPhoneticKey.get().get(key);
        if (entry == null) return Optional.empty();

        // The key is deliberately lossy, so it produces false friends:
        // "britannia" and "bartan" both reduce to "brtn". Confirming with an
        // edit distance against the stored term is what stops a real brand
        // name being translated into dishwash - the key finds candidates
        // cheaply, this decides whether they are actually the same word.
        if (!SearchNormalizer.areCloseEnough(token, entry.term())) return Optional.empty();

        String canonical = entry.canonical();

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
            Map<String, Entry> rebuilt = new HashMap<>();
            for (SearchSynonym synonym : repository.findByActiveTrue()) {
                String key = SearchNormalizer.phoneticKey(synonym.getTerm());
                if (key.isEmpty()) continue;

                Entry entry = new Entry(synonym.getTerm(), synonym.getCanonicalTerm());
                Entry existing = rebuilt.putIfAbsent(key, entry);
                if (existing != null && !existing.canonical().equals(entry.canonical())) {
                    log.warn("Search synonym '{}' collides on phonetic key '{}' with '{}'; keeping '{}'",
                            synonym.getTerm(), key, existing.term(), existing.term());
                }
            }
            byPhoneticKey.set(Map.copyOf(rebuilt));
            log.debug("Loaded {} search synonyms", rebuilt.size());
        } catch (Exception e) {
            // Search must keep working without the dictionary - it simply
            // loses Hindi/Hinglish translation and falls back to the
            // phonetic and trigram layers, which need no data at all.
            log.warn("Could not load search synonyms; Smart Search continues without translation: {}", e.getMessage());
        }
    }

    /** For tests and diagnostics. */
    public int size() {
        return byPhoneticKey.get().size();
    }
}
