package com.gpstore.search;

import com.gpstore.entity.SearchSynonym;
import com.gpstore.repository.SearchSynonymRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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

    private final SearchSynonymRepository repository;

    private final AtomicReference<Map<String, String>> byPhoneticKey =
            new AtomicReference<>(Map.of());

    public SynonymDictionary(SearchSynonymRepository repository) {
        this.repository = repository;
        refresh();
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

        String canonical = byPhoneticKey.get().get(key);
        if (canonical == null) return Optional.empty();

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
            Map<String, String> rebuilt = new HashMap<>();
            for (SearchSynonym synonym : repository.findByActiveTrue()) {
                String key = SearchNormalizer.phoneticKey(synonym.getTerm());
                if (key.isEmpty()) continue;

                String existing = rebuilt.putIfAbsent(key, synonym.getCanonicalTerm());
                if (existing != null && !existing.equals(synonym.getCanonicalTerm())) {
                    log.warn("Search synonym '{}' collides on phonetic key '{}' with '{}'; keeping '{}'",
                            synonym.getTerm(), key, existing, existing);
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
