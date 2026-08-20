package com.gpstore.search;

import com.gpstore.dto.response.ProductResponse;
import com.gpstore.exception.BadRequestException;
import com.gpstore.service.ProductService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Smart Search: the layers, in the order they are tried.
 *
 * <ol>
 *   <li><b>As typed.</b> The existing instant search - exact, prefix and
 *       partial matches, with pg_trgm handling ordinary typos and ranking by
 *       similarity. Most searches stop here, and they stop here at exactly
 *       the speed they did before this class existed.</li>
 *   <li><b>Translated.</b> If nothing matched, each token is looked up in the
 *       Hindi/Hinglish dictionary by phonetic key ("chini", "cheeni" and
 *       "chinee" all reach the row stored as "chini") and the search is
 *       retried with the English word.</li>
 *   <li><b>Narrowed.</b> If that still finds nothing, the query is retried
 *       one token at a time, longest first. This is what makes "chini 1 kilo"
 *       and "fortn oil 5l" work: the quantity is real to the customer but is
 *       rarely part of a product's name, so it has to be droppable.</li>
 * </ol>
 *
 * <p>NOTHING IS PRECOMPUTED ONTO PRODUCTS. No denormalised search column, no
 * copy of the catalogue, no backfill, and nothing to rebuild when a product
 * is edited - a new product is searchable the moment it is saved, because
 * every layer works off the product's own name and brand through the indexes
 * that already exist.
 *
 * <p>THE ORIGINAL ENDPOINTS ARE UNTOUCHED. This is additive; /search and
 * /search/instant behave exactly as before.
 */
@Service
public class SmartSearchService {

    /**
     * How many tokens of a query are worth trying individually. A long query
     * that has already failed twice is not going to be rescued by its
     * fourth-longest word, and each attempt is a database round trip.
     */
    private static final int MAX_TOKEN_ATTEMPTS = 3;

    private final ProductService productService;
    private final SynonymDictionary synonyms;

    public SmartSearchService(ProductService productService, SynonymDictionary synonyms) {
        this.productService = productService;
        this.synonyms = synonyms;
    }

    public SmartSearchResult search(String rawQuery, Pageable pageable) {
        if (rawQuery == null || rawQuery.isBlank()) {
            throw new BadRequestException("Search keyword is required");
        }

        String query = rawQuery.trim();

        // LAYER 1 - as typed.
        Page<ProductResponse> asTyped = productService.searchInstant(query, pageable);
        Optional<String> translated = translate(query);

        if (asTyped.hasContent() && answersWholeQuery(query, asTyped.getContent())) {
            // The results are right, but the customer may still have
            // misspelled the word - "sogar" finds Sugar through trigram
            // similarity without anyone saying so. Naming what was understood
            // is the difference between a search that works and one the
            // customer can trust.
            String correction = deriveCorrection(query, asTyped.getContent());
            return SmartSearchResult.matched(query, correction, asTyped);
        }

        // LAYER 2 - translated.
        //
        // Reached when the query found nothing AND when it found something
        // that only answers PART of what was asked. The second case is the
        // one that is easy to miss: "amul chini" matches plenty of Amul
        // products on the brand alone, so a plain "did layer 1 return rows"
        // check would stop here and quietly never translate "chini" - the
        // customer would get Amul milk and no sugar. Ordering translation
        // first instead would break the opposite case, since "atta" is a
        // word that really does appear in product names.
        if (translated.isPresent()) {
            Page<ProductResponse> results = productService.searchInstant(translated.get(), pageable);
            if (results.hasContent()) {
                // High confidence: the dictionary is an explicit statement
                // that this word means that word, so the corrected term is
                // applied rather than offered.
                return SmartSearchResult.interpreted(query, translated.get(), results);
            }
        }

        // The partial answer from layer 1 is better than nothing, so it is
        // kept rather than discarded just because translation found no more.
        if (asTyped.hasContent()) {
            return SmartSearchResult.matched(query, deriveCorrection(query, asTyped.getContent()), asTyped);
        }

        // LAYER 3 - narrowed, longest token first.
        for (String token : significantTokens(query)) {
            String attempt = synonyms.canonicalFor(token).orElse(token);
            Page<ProductResponse> results = productService.searchInstant(attempt, pageable);
            if (results.hasContent()) {
                // Lower confidence: part of what the customer asked for was
                // dropped to get here, so this is offered as a suggestion and
                // the query is left as they typed it.
                return SmartSearchResult.suggested(query, attempt, results);
            }
        }

        return SmartSearchResult.empty(query, asTyped);
    }

    /**
     * Whether the results account for every meaningful word the customer
     * typed, literally or phonetically.
     *
     * <p>This is what distinguishes "found it" from "found something". A
     * query whose brand matched but whose product word did not has not been
     * answered, and should fall through to the next layer.
     *
     * <p>Digits and very short words are ignored: quantities and units are
     * rarely part of a product's name, and requiring them would send every
     * "sugar 1kg" down the fallback path for no reason.
     */
    private boolean answersWholeQuery(String query, List<ProductResponse> results) {
        Set<String> resultWords = wordsIn(results);

        for (String token : SearchNormalizer.tokenize(query)) {
            if (token.length() < 3 || token.chars().anyMatch(Character::isDigit)) continue;
            if (resultWords.contains(token)) continue;

            String key = SearchNormalizer.phoneticKey(token);
            if (!key.isEmpty() && firstWordWithKey(resultWords, key) != null) continue;

            return false;
        }
        return true;
    }

    /** The distinct words appearing in the names and brands of the best few results. */
    private Set<String> wordsIn(List<ProductResponse> results) {
        Set<String> words = new LinkedHashSet<>();
        // Only the best few. A word taken from the twentieth match is noise.
        for (ProductResponse product : results.subList(0, Math.min(3, results.size()))) {
            words.addAll(SearchNormalizer.tokenize(product.getName()));
            if (product.getBrand() != null) {
                words.addAll(SearchNormalizer.tokenize(product.getBrand()));
            }
        }
        return words;
    }

    /**
     * Rewrites a query with every token the dictionary recognises replaced by
     * its English equivalent, or empty if it recognises none.
     *
     * <p>Token by token rather than whole-string, so mixed queries work:
     * "amul doodh" becomes "amul milk" with the brand left alone.
     */
    private Optional<String> translate(String query) {
        List<String> tokens = SearchNormalizer.tokenize(query);
        if (tokens.isEmpty()) return Optional.empty();

        List<String> rewritten = new ArrayList<>(tokens.size());
        boolean changed = false;

        for (String token : tokens) {
            Optional<String> canonical = synonyms.canonicalFor(token);
            if (canonical.isPresent()) {
                rewritten.add(canonical.get());
                changed = true;
            } else {
                rewritten.add(token);
            }
        }

        return changed ? Optional.of(String.join(" ", rewritten)) : Optional.empty();
    }

    /** Tokens worth searching alone, longest first, capped. */
    private List<String> significantTokens(String query) {
        List<String> tokens = new ArrayList<>(SearchNormalizer.tokenize(query));
        // Longest first: in "chini 1 kilo" the product word is the long one,
        // and a single-token query is already covered by layer 1.
        tokens.sort((a, b) -> Integer.compare(b.length(), a.length()));
        tokens.removeIf(t -> t.length() < 3);
        return tokens.size() <= MAX_TOKEN_ATTEMPTS ? tokens : tokens.subList(0, MAX_TOKEN_ATTEMPTS);
    }

    /**
     * Works out which word the customer was reaching for, by looking at what
     * actually matched rather than by guessing.
     *
     * <p>If a query token appears literally in the results, there is nothing
     * to correct. If it does not, but some word in a result shares its
     * phonetic key, that word is what they meant - "sogar" against "Tata
     * Sugar 1kg" finds "sugar", because both reduce to "sgr".
     *
     * <p>Returns null when nothing can be said with confidence, which is the
     * common case and must stay silent rather than inventing a correction.
     */
    private String deriveCorrection(String query, List<ProductResponse> results) {
        List<String> queryTokens = SearchNormalizer.tokenize(query);
        if (queryTokens.isEmpty() || results.isEmpty()) return null;

        Set<String> resultWords = wordsIn(results);

        List<String> corrected = new ArrayList<>(queryTokens.size());
        boolean changed = false;

        for (String token : queryTokens) {
            if (resultWords.contains(token)) {
                corrected.add(token);
                continue;
            }

            String key = SearchNormalizer.phoneticKey(token);
            String match = key.isEmpty() ? null : firstWordWithKey(resultWords, key);

            if (match != null) {
                corrected.add(match);
                changed = true;
            } else {
                // A token that matched neither literally nor phonetically is
                // left as typed - it may be a quantity, or a word the
                // customer got right that simply is not in the top results.
                corrected.add(token);
            }
        }

        return changed ? String.join(" ", corrected) : null;
    }

    private String firstWordWithKey(Set<String> words, String key) {
        for (String word : words) {
            if (key.equals(SearchNormalizer.phoneticKey(word))) return word;
        }
        return null;
    }
}
