package com.gpstore.search;

import com.gpstore.dto.response.ProductResponse;
import org.springframework.data.domain.Page;

/**
 * What Smart Search understood, alongside what it found.
 *
 * <p>The two correction fields are deliberately separate, because they mean
 * different things to the customer and the UI must not treat them alike:
 *
 * <ul>
 *   <li>{@code interpretedAs} - high confidence. The search was run on this
 *       instead of what was typed. Show it: "Showing results for sugar".</li>
 *   <li>{@code didYouMean} - low confidence. The results came from a narrowed
 *       or partial reading, and the customer's own words were kept. Offer it:
 *       "Did you mean sugar?" and let them decide.</li>
 * </ul>
 *
 * <p>Both null is the ordinary case - a search that worked as typed - and
 * silence is the correct output for it. Aggressively "correcting" a query
 * that was already right is worse than not correcting at all.
 */
public class SmartSearchResult {

    private final String query;
    private final String interpretedAs;
    private final String didYouMean;
    private final Page<ProductResponse> results;

    private SmartSearchResult(String query, String interpretedAs, String didYouMean,
                              Page<ProductResponse> results) {
        this.query = query;
        this.interpretedAs = interpretedAs;
        this.didYouMean = didYouMean;
        this.results = results;
    }

    /** Found as typed. {@code correction} may be null when nothing was misspelled. */
    static SmartSearchResult matched(String query, String correction, Page<ProductResponse> results) {
        return new SmartSearchResult(query, correction, null, results);
    }

    /** Found by translating the query - the dictionary is an explicit mapping, so this is applied. */
    static SmartSearchResult interpreted(String query, String interpretedAs, Page<ProductResponse> results) {
        return new SmartSearchResult(query, interpretedAs, null, results);
    }

    /** Found only after dropping part of the query, so it is offered rather than applied. */
    static SmartSearchResult suggested(String query, String suggestion, Page<ProductResponse> results) {
        return new SmartSearchResult(query, null, suggestion, results);
    }

    static SmartSearchResult empty(String query, Page<ProductResponse> emptyPage) {
        return new SmartSearchResult(query, null, null, emptyPage);
    }

    public String getQuery() {
        return query;
    }

    public String getInterpretedAs() {
        return interpretedAs;
    }

    public String getDidYouMean() {
        return didYouMean;
    }

    public Page<ProductResponse> getResults() {
        return results;
    }
}
