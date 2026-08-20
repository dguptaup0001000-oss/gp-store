package com.gpstore.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The phonetic key is the whole of Smart Search's spelling tolerance, so
 * these are written as the question that matters: does every way a customer
 * might type this word land on the same key?
 */
class SearchNormalizerTest {

    private void allAgree(String... spellings) {
        String expected = SearchNormalizer.phoneticKey(spellings[0]);
        assertThat(expected)
                .as("'%s' must produce a usable key", spellings[0])
                .isNotEmpty();

        for (String spelling : spellings) {
            assertThat(SearchNormalizer.phoneticKey(spelling))
                    .as("'%s' should key the same as '%s'", spelling, spellings[0])
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("ordinary typos land on the same key as the word")
    void typos() {
        allAgree("sugar", "sogar", "sugr", "shugar");
        allAgree("salt", "solt");
        allAgree("turmeric", "turmerc");
        allAgree("fortune", "fortn");
        allAgree("namak", "namk");
    }

    @Test
    @DisplayName("Hinglish spellings of one word land on the same key")
    void transliterations() {
        allAgree("chawal", "chaval", "chaawal");
        allAgree("haldi", "haldhi");
        allAgree("doodh", "dudh");
        allAgree("chini", "cheeni", "chinee");
        allAgree("dal", "daal");
        allAgree("tel", "tael");
        allAgree("sabun", "saban", "saboon");
        allAgree("atta", "aata", "aataa", "ata");
    }

    @Test
    @DisplayName("v and w are one sound, which is why the brand is typed both ways")
    void brandSpellings() {
        allAgree("aashirvaad", "ashirvad", "ashirwad", "aashirwaad");
    }

    @Test
    @DisplayName("different words keep different keys")
    void doesNotCollapseEverything() {
        // The failure mode that would make search useless: a key so lossy
        // that unrelated products all match.
        List<String> distinct = List.of("milk", "rice", "soap", "oil", "salt", "sugar", "turmeric");
        assertThat(distinct.stream().map(SearchNormalizer::phoneticKey).distinct())
                .hasSize(distinct.size());
    }

    @Test
    @DisplayName("the leading letter is kept, so atta and otta stay apart")
    void leadingVowelIsSignificant() {
        assertThat(SearchNormalizer.phoneticKey("atta"))
                .isNotEqualTo(SearchNormalizer.phoneticKey("otta"));
    }

    @Test
    @DisplayName("doubled letters collapse only when they are actually adjacent")
    void adjacencyMatters() {
        // "atta" -> "ata": adjacent, so they collapse.
        assertThat(SearchNormalizer.phoneticKey("atta")).isEqualTo(SearchNormalizer.phoneticKey("ata"));

        // "dudh" -> the two d's are separated by a vowel and are two real
        // sounds. Collapsing them would reduce the word to a single letter
        // and lose it entirely.
        assertThat(SearchNormalizer.phoneticKey("dudh")).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("words with digits get no phonetic opinion")
    void quantitiesAreNotPhonetic() {
        // "1kg" is not a misspelling of anything; treating it phonetically
        // would match it against real words.
        assertThat(SearchNormalizer.phoneticKey("1kg")).isEmpty();
        assertThat(SearchNormalizer.phoneticKey("500ml")).isEmpty();
    }

    @Test
    @DisplayName("a key too short to be meaningful is refused rather than returned")
    void refusesUselessKeys() {
        // A one-letter key would match a large slice of the catalogue.
        assertThat(SearchNormalizer.phoneticKey("a")).isEmpty();
        assertThat(SearchNormalizer.phoneticKey("")).isEmpty();
        assertThat(SearchNormalizer.phoneticKey(null)).isEmpty();
    }

    @Test
    @DisplayName("normalization strips punctuation, case and accents but keeps digits")
    void normalization() {
        assertThat(SearchNormalizer.normalize("  TATA   Salt!! ")).isEqualTo("tata salt");
        assertThat(SearchNormalizer.normalize("Amul-Milk (1L)")).isEqualTo("amul milk 1l");
        assertThat(SearchNormalizer.normalize("Café")).isEqualTo("cafe");
        assertThat(SearchNormalizer.normalize(null)).isEmpty();
    }

    @Test
    @DisplayName("tokenizing drops blanks and repeats but keeps order")
    void tokenizing() {
        assertThat(SearchNormalizer.tokenize("  Tata   Salt  Tata ")).containsExactly("tata", "salt");
        assertThat(SearchNormalizer.tokenize("chini 1 kilo")).containsExactly("chini", "1", "kilo");
        assertThat(SearchNormalizer.tokenize("   ")).isEmpty();
    }

    @Test
    @DisplayName("phonetic keys are produced per token, skipping unusable ones")
    void keysPerToken() {
        // "1" has no key; "chini" does. The quantity must not silently
        // become a phonetic match for something.
        assertThat(SearchNormalizer.phoneticKeys("chini 1 kilo")).doesNotContain("");
        assertThat(SearchNormalizer.phoneticKeys("chini 1 kilo")).hasSize(2);
    }
}
