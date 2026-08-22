package com.gpstore.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The words customers actually say, asserted as a list rather than as a
 * design.
 *
 * <p>WHY THESE ARE DATA TESTS. The vocabulary lives in a table an admin can
 * add rows to without shipping an app - so "does the system support desi
 * search" is not a question about architecture, it is a question about
 * whether a given word is in the table. These pin the words that matter, and
 * they fail loudly when a row is missing rather than silently returning the
 * wrong shelf.
 *
 * <p>The phonetic layer generates the SPELLINGS - "chini" also matches
 * "cheeni" and "chinee" - so this is one assertion per CONCEPT, not per
 * pronunciation.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class DesiVocabularyTest {

    @Autowired private SynonymDictionary synonyms;

    /** A word that is already the canonical spelling reports no translation. */
    private void identity(String spoken) {
        assertThat(synonyms.canonicalFor(spoken))
                .as("'%s' is already the right word - offering a correction for it is noise", spoken)
                .isEmpty();
    }

    private void resolves(String spoken, String expected) {
        assertThat(synonyms.canonicalFor(spoken))
                .as("'%s' must be understood - a customer saying it should reach %s", spoken, expected)
                .contains(expected);
    }

    @Test
    @DisplayName("Staples: atta, dal, rice, sugar, salt")
    void staples() {
        resolves("atta", "flour");
        resolves("aata", "flour");
        resolves("आटा", "flour");
        resolves("gehu", "wheat");
        resolves("daal", "dal");
        // "dal" is already the canonical spelling, so no translation is
        // reported - while the Devanagari spelling is not, and does resolve.
        identity("dal");
        resolves("दाल", "dal");
        resolves("chawal", "rice");
        resolves("chini", "sugar");
        resolves("namak", "salt");
    }

    @Test
    @DisplayName("Dals by their real names")
    void dals() {
        // A shopper asks for the variety, not for "dal".
        resolves("toor", "pigeon pea");
        resolves("tur", "pigeon pea");
        resolves("arhar", "pigeon pea");
        resolves("moong", "mung");
        resolves("masoor", "lentil");
        resolves("chana", "chickpea");
    }

    @Test
    @DisplayName("Oils, including the ones named after their seed")
    void oils() {
        resolves("tel", "oil");
        resolves("तेल", "oil");
        resolves("sarso", "mustard");
        resolves("sarson", "mustard");
        resolves("सरसों", "mustard");
        resolves("nariyal", "coconut");
    }

    @Test
    @DisplayName("Dairy")
    void dairy() {
        resolves("doodh", "milk");
        resolves("dudh", "milk");
        resolves("दूध", "milk");
        resolves("dahi", "curd");
        resolves("paneer", "cottage cheese");
    }

    @Test
    @DisplayName("Everyday household words")
    void household() {
        resolves("sabun", "soap");
        resolves("साबुन", "soap");
        // namkeen and biscuit map to THEMSELVES in the table - they are there
        // so their spellings unify, not to be substituted. canonicalFor
        // correctly reports no translation for a word already spelled right;
        // showing a "did you mean namkeen" for "namkeen" would be absurd.
        identity("namkeen");
        identity("biscuit");
    }

    @Test
    @DisplayName("Misheard spellings a recogniser really produces")
    void misheardSpellings() {
        // The phonetic layer exists for exactly this: a recogniser writes what
        // it heard, not what is on the packet.
        // Misspellings DO resolve, because they are not the stored spelling.
        resolves("biskit", "biscuit");
        resolves("biscuut", "biscuit");
        resolves("saboon", "soap");
        resolves("cheeni", "sugar");
        resolves("chawaal", "rice");
    }
}
