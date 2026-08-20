package com.gpstore.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns what a customer typed into something comparable.
 *
 * <p>THE ONE IDEA. Every spelling of an Indian grocery word that a customer
 * might type differs almost entirely in its VOWELS, while the consonants stay
 * put:
 *
 * <pre>
 *   chawal / chaval / chaawal      doodh / dudh
 *   aata / aataa / atta            haldi / haldhi
 *   aashirvaad / ashirvad / ashirwad
 * </pre>
 *
 * The same is true of ordinary typos - sogar/sugar, namk/namak, solt/salt,
 * turmerc/turmeric, fortn/fortune - which are overwhelmingly vowel slips,
 * doubled letters and dropped letters rather than wholesale consonant
 * changes. So one key that keeps the first letter and the consonant skeleton,
 * with the common Indian-transliteration consonant pairs folded together,
 * covers transliteration and typos at the same time, with no per-word rules
 * and nothing to maintain as the catalogue grows.
 *
 * <pre>
 *   sugar, sogar, shugar, sugr  -> sgr
 *   chawal, chaval, chaawal     -> cvl
 *   aashirvaad, ashirwad        -> asrvd
 *   turmeric, turmerc           -> trmrk
 * </pre>
 *
 * <p>WHAT THIS DELIBERATELY CANNOT DO. No amount of string manipulation gets
 * from "chini" to "sugar" - that is a translation, not a spelling. Those come
 * from {@link SynonymDictionary}, a small table of canonical Hindi grocery
 * words. The split matters: the dictionary holds roughly one row per CONCEPT
 * ("chini" -> "sugar"), and this class generates every SPELLING of it, so the
 * table stays small and hand-maintainable instead of becoming the enormous
 * list of variants the brief rules out.
 */
public final class SearchNormalizer {

    private SearchNormalizer() {
    }

    /** Keys shorter than this match far too much to be useful. */
    public static final int MIN_KEY_LENGTH = 2;

    private static final Set<Character> VOWELS = Set.of('a', 'e', 'i', 'o', 'u');

    /**
     * Lowercases, strips accents, and reduces anything that is not a letter or
     * a digit to a single space.
     *
     * <p>Digits survive because they carry meaning in this domain - "1kg",
     * "500ml", "5 kg" are things customers actually search for.
     */
    public static String normalize(String raw) {
        if (raw == null) return "";

        String decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");

        StringBuilder out = new StringBuilder(decomposed.length());
        boolean lastWasSpace = true; // leading spaces are dropped
        for (char c : decomposed.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
                lastWasSpace = false;
            } else if (!lastWasSpace) {
                out.append(' ');
                lastWasSpace = true;
            }
        }
        return out.toString().trim();
    }

    /** Normalized words, in order, with duplicates and blanks removed. */
    public static List<String> tokenize(String raw) {
        String normalized = normalize(raw);
        if (normalized.isEmpty()) return List.of();

        Set<String> unique = new LinkedHashSet<>(Arrays.asList(normalized.split(" ")));
        unique.remove("");
        return new ArrayList<>(unique);
    }

    /**
     * The consonant skeleton described in the class doc.
     *
     * <p>Returns an empty string when the word is too short or has no
     * consonants to build a key from - callers must treat that as "no
     * phonetic opinion" rather than as a key that matches everything.
     */
    public static String phoneticKey(String word) {
        String w = normalize(word);
        if (w.isEmpty()) return "";
        // Digits are meaningful as themselves ("1kg"); they are never
        // phonetically confused with anything, so they get no key.
        if (w.chars().anyMatch(Character::isDigit)) return "";

        w = foldDigraphs(w);

        StringBuilder key = new StringBuilder(w.length());
        char previous = 0;

        for (int i = 0; i < w.length(); i++) {
            char c = foldConsonant(w.charAt(i));

            // Doubled letters collapse, but ONLY when they are adjacent in
            // the word itself - "atta" and "ata" are one word, whereas the
            // two d's of "dudh" are separated by a vowel and are two real
            // sounds. Comparing against the previous SOURCE letter rather
            // than the last letter written to the key is what keeps those
            // apart; comparing against the key would reduce "dudh" to a
            // single "d" and lose the word entirely.
            boolean isAdjacentRepeat = c == previous;
            previous = c;
            if (isAdjacentRepeat) continue;

            // The first letter is kept even when it is a vowel: "atta" and
            // "otta" are different words, and dropping the lead would merge
            // them. Everywhere else vowels are noise - they are exactly what
            // varies between spellings.
            if (i == 0) {
                key.append(c);
                continue;
            }
            if (VOWELS.contains(c)) continue;

            key.append(c);
        }

        String result = key.toString();
        return result.length() < MIN_KEY_LENGTH ? "" : result;
    }

    /** Phonetic keys for every token, skipping ones too short to be useful. */
    public static List<String> phoneticKeys(String raw) {
        List<String> keys = new ArrayList<>();
        for (String token : tokenize(raw)) {
            String key = phoneticKey(token);
            if (!key.isEmpty()) keys.add(key);
        }
        return keys;
    }

    /**
     * Two-letter combinations that stand for one sound in Indian
     * transliteration. Folded before the single-letter pass so "sh" becomes
     * one consonant rather than two.
     *
     * <p>Order matters - the aspirated pairs must be folded before "h" can be
     * treated as a consonant in its own right, or "haldhi" and "haldi" would
     * end up with different keys.
     */
    private static String foldDigraphs(String w) {
        return w
                // Aspirated consonants: Hindi distinguishes them, English
                // transliteration of grocery words mostly does not, and
                // customers type them either way (haldi/haldhi, dudh/doodh).
                .replace("bh", "b")
                .replace("ch", "c")
                .replace("dh", "d")
                .replace("gh", "g")
                .replace("jh", "j")
                .replace("kh", "k")
                .replace("ph", "f")
                .replace("th", "t")
                // Sibilants: "sh" and "s" are interchangeable in how these
                // words get typed (shakkar/sakkar, shugar/sugar).
                .replace("sh", "s")
                .replace("ck", "k")
                .replace("qu", "k");
    }

    /**
     * Single letters that carry the same sound in this domain.
     *
     * <p>v/w because Hindi has one sound for both, which is why the same
     * brand is typed "ashirvad" and "ashirwad". The rest are the standard
     * English confusions: c/k, z/s, y treated as a vowel.
     */
    private static char foldConsonant(char c) {
        switch (c) {
            case 'w':
                return 'v';
            case 'c':
            case 'k':
            case 'q':
                // One sound, two letters, and customers use both -
                // "kachori"/"cachori", "kaju"/"caju".
                return 'k';
            case 'z':
                return 's';
            case 'y':
                // Vowel-like: "dahi"/"dahee" and "ghee"/"ghy" should agree.
                return 'i';
            default:
                return c;
        }
    }
}
