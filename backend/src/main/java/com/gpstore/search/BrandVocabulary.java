package com.gpstore.search;

import com.gpstore.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The brands this shop actually stocks, used to repair what a speech
 * recogniser got wrong.
 *
 * WHY THIS EXISTS. A customer said "aachi haldi" three times. Google's
 * recogniser heard "रांची हल्दी" every time, which romanises to "ranchi
 * haldi", and the shop dutifully searched for a brand nobody sells and
 * announced "Showing results for ranchi turmeric".
 *
 * Aachi is stocked. "ranchi" is two edits from "aachi". Nothing in the
 * pipeline knew that, because the dictionary holds VOCABULARY - haldi,
 * chini, doodh - and a brand name is not vocabulary. Brands are catalogue
 * data: they arrive when the shopkeeper adds a product and they change
 * whenever the shelf does, so they cannot be a hand-maintained list. This
 * reads them from the catalogue itself.
 *
 * DELIBERATELY A LAST RESORT. Correcting a word to a brand is only safe when
 * the word found nothing on its own - "catch" is a real brand AND close to
 * "match", and a shop that silently rewrites what people type is worse than
 * one that occasionally finds nothing. SmartSearchService only consults this
 * after the query as typed, its romanised form and the dictionary have all
 * failed to answer.
 *
 * Refreshed on the same schedule as SynonymDictionary and swapped behind an
 * AtomicReference, so a refresh never leaves a half-built list visible to a
 * request in flight.
 */
@Component
public class BrandVocabulary {

    private static final Logger log = LoggerFactory.getLogger(BrandVocabulary.class);

    /** Same cadence as the synonym dictionary - brands change with the shelf. */
    private static final long REFRESH_MS = 10 * 60 * 1000L;

    /**
     * A brand must be at least this long to be corrected TO.
     *
     * Short names are close to everything: at three characters almost any
     * typo is "one edit away", and correcting to one would be a coin toss
     * dressed up as intelligence.
     */
    private static final int MIN_BRAND_LENGTH = 4;

    private final ProductRepository productRepository;
    private final AtomicReference<List<String>> brands = new AtomicReference<>(List.of());

    public BrandVocabulary(ProductRepository productRepository) {
        this.productRepository = productRepository;
        refresh();
    }

    @Scheduled(fixedDelay = REFRESH_MS)
    public final void refresh() {
        try {
            List<String> loaded = new ArrayList<>();
            for (Object[] row : productRepository.findBrandsWithProductCounts()) {
                String brand = (String) row[0];
                if (brand != null && brand.trim().length() >= MIN_BRAND_LENGTH) {
                    loaded.add(brand.trim());
                }
            }
            brands.set(List.copyOf(loaded));
            log.debug("Brand vocabulary refreshed: {} brands", loaded.size());
        } catch (Exception e) {
            // Never take search down over this. An empty or stale vocabulary
            // just means no brand correction, which is how the shop behaved
            // before this class existed.
            log.warn("Could not refresh brand vocabulary; keeping the previous one.", e);
        }
    }

    /** Every stocked brand, as written in the catalogue. */
    public List<String> all() {
        return brands.get();
    }

    /**
     * The stocked brand [token] was most likely meant to be, if any.
     *
     * Compares against the brand's own spelling AND its phonetic key, because
     * the two failures this repairs are different: a recogniser hearing a
     * similar-sounding word ("ranchi" for "aachi") is caught by edit distance,
     * while a customer spelling one by ear ("aashirwad" for "Aashirvaad") is
     * caught phonetically.
     *
     * The distance budget scales with length - one edit on a five-letter
     * brand, two on a longer one - because a fixed budget of two turns every
     * four-letter word into a match for every other.
     */
    public Optional<String> closestBrand(String token) {
        if (token == null) return Optional.empty();
        String needle = token.trim().toLowerCase(Locale.ROOT);
        if (needle.length() < MIN_BRAND_LENGTH) return Optional.empty();

        // Two edits from six characters, one below that. Tuned on the case
        // that prompted this: "ranchi" -> "aachi" is two edits over six
        // letters, and a budget of one - which is what a >=7 threshold gives -
        // silently did nothing while looking like it worked. Five letters and
        // under stay at one edit, because at that length two edits reach most
        // of the dictionary.
        int budget = needle.length() >= 6 ? 2 : 1;
        String needleKey = SearchNormalizer.phoneticKey(needle);

        String best = null;
        int bestDistance = Integer.MAX_VALUE;

        for (String brand : brands.get()) {
            String candidate = brand.toLowerCase(Locale.ROOT);

            // Already the brand - nothing to correct.
            if (candidate.equals(needle)) return Optional.empty();

            int distance = SearchNormalizer.editDistance(needle, candidate, budget + 1);
            boolean soundsLike = !needleKey.isEmpty()
                    && needleKey.equals(SearchNormalizer.phoneticKey(candidate));

            // A phonetic match earns one extra edit, not unlimited licence:
            // sounding alike is strong evidence, but "aashirwad"/"Aashirvaad"
            // still has to be recognisably the same word.
            int allowed = soundsLike ? budget + 1 : budget;

            if (distance <= allowed && distance < bestDistance) {
                bestDistance = distance;
                best = brand;
            }
        }

        return Optional.ofNullable(best);
    }
}
