package com.gpstore.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

        String decomposed = Normalizer.normalize(transliterate(raw), Normalizer.Form.NFD)
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

    /**
     * Levenshtein edit distance, bounded.
     *
     * <p>The phonetic key is deliberately lossy, which means it produces
     * false friends: "britannia" and "bartan" both reduce to "brtn", and
     * without a second opinion a real brand name would be translated to
     * "dishwash". This is that second opinion - the key finds candidates
     * cheaply, and this confirms they are actually the same word.
     *
     * <p>Bounded because the answer is only ever compared against a small
     * threshold: once the distance exceeds [limit] the exact value does not
     * matter, so the rest of the matrix is not worth computing.
     */
    public static int editDistance(String a, String b, int limit) {
        String left = normalize(a);
        String right = normalize(b);

        if (left.equals(right)) return 0;
        if (Math.abs(left.length() - right.length()) > limit) return limit + 1;

        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;

        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            int bestInRow = current[0];

            for (int j = 1; j <= right.length(); j++) {
                int substitution = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
                bestInRow = Math.min(bestInRow, current[j]);
            }

            // Every remaining row can only increase the distance, so once an
            // entire row is past the limit the answer is too.
            if (bestInRow > limit) return limit + 1;

            int[] swap = previous;
            previous = current;
            current = swap;
        }

        return previous[right.length()];
    }

    /**
     * Whether two words are close enough to be the same word, given that
     * their phonetic keys already agree.
     *
     * <p>Two edits covers the real variants - cheeni/chini, dudh/doodh,
     * chaawal/chawal, saboon/sabun - while rejecting the key collisions that
     * are simply different words. The allowance grows slowly for longer
     * words, where more of the spelling can drift without changing which word
     * is meant.
     */
    public static boolean areCloseEnough(String a, String b) {
        int shorter = Math.min(normalize(a).length(), normalize(b).length());
        int limit = Math.max(2, shorter / 3);
        return editDistance(a, b, limit) <= limit;
    }

    /**
     * Devanagari to Latin, so Hindi script reaches the same vocabulary as
     * Hinglish typing.
     *
     * <p>WHY THIS IS NEEDED AT ALL. normalize() kept Devanagari happily -
     * those characters are letters, so they survived - but phoneticKey folds
     * LATIN consonants. A word written in Hindi therefore produced a key made
     * of Devanagari characters, which could never equal the key of the same
     * word typed in Hinglish. "आटा" and "atta" are the same order and were
     * two unrelated strings. Voice makes this urgent rather than theoretical:
     * an Android recogniser set to hi-IN returns Devanagari for everything.
     *
     * <p>CHARACTER BY CHARACTER, AND NO INHERENT VOWEL. A faithful
     * transliteration would insert the implicit "a" that every Devanagari
     * consonant carries - "कल" is "kal", not "kl". This deliberately does
     * not, because the only consumer is a pipeline that strips vowels
     * anyway: phoneticKey reduces both "kal" and "kl" to "kl". Adding the
     * rule would be more code, more edge cases around the virama, and
     * exactly zero difference to any key. The one place the vowels do matter
     * is trigram similarity against a product name, and there the vowels a
     * matra produces - the "aa" of आटा - are the ones that carry the sound.
     *
     * <p>Returns the input unchanged when it holds no Devanagari, which is
     * almost every query, so the common path costs one scan and no
     * allocation.
     */
    public static String transliterate(String raw) {
        if (raw == null) return "";
        if (!containsDevanagari(raw)) return raw;

        StringBuilder out = new StringBuilder(raw.length() * 2);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);

            // NUKTA LOOKAHEAD. The borrowed sounds of Urdu and English
            // loanwords - the z of "ज़", the f of "फ़" - are written as a base
            // consonant followed by a combining dot, U+093C. They are two
            // code points, not one, so they cannot be a Java char literal and
            // have to be recognised as a pair. Skipping this would spell
            // Fortune as "phorchyoon" and zyada as "jyada".
            if (i + 1 < raw.length() && raw.charAt(i + 1) == NUKTA) {
                String nuktaForm = NUKTA_FORMS.get(c);
                if (nuktaForm != null) {
                    out.append(nuktaForm);
                    i++; // the dot itself is consumed with the consonant
                    continue;
                }
            }

            String mapped = DEVANAGARI.get(c);
            if (mapped != null) {
                out.append(mapped);
            } else if (c >= DEVANAGARI_START && c <= DEVANAGARI_END) {
                // An unmapped Devanagari character - a rare sign or a
                // combining mark. Dropped rather than passed through, because
                // passing it through would put a character into the key that
                // no Latin spelling can ever produce.
                continue;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Whether a query is written in Hindi script at all. */
    public static boolean containsDevanagari(String raw) {
        if (raw == null) return false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= DEVANAGARI_START && c <= DEVANAGARI_END) return true;
        }
        return false;
    }

    private static final char DEVANAGARI_START = '\u0900';
    private static final char DEVANAGARI_END = '\u097F';

    /** The combining dot that turns a consonant into its borrowed-sound form. */
    private static final char NUKTA = '\u093C';

    /** Base consonant -> the sound it makes when a nukta follows it. */
    private static final Map<Character, String> NUKTA_FORMS = Map.of(
            'क', "q",
            'ख', "kh",
            'ग', "g",
            'ज', "z",
            'ड', "r",
            'ढ', "rh",
            'फ', "f",
            'य', "y");

    private static final Map<Character, String> DEVANAGARI = buildDevanagari();

    private static Map<Character, String> buildDevanagari() {
        Map<Character, String> m = new HashMap<>();

        // Independent vowels.
        //
        // SHORT LATIN VOWELS, INCLUDING FOR THE LONG DEVANAGARI ONES. A
        // faithful scheme writes ी as "ee" and ू as "oo", and doing that here
        // was a real bug: "चीनी" became "cheenee", which is four edits from
        // the "chini" the vocabulary stores - past areCloseEnough's threshold,
        // so the word resolved to nothing and Hindi sugar found no sugar.
        //
        // Mapping them short instead lands on the spelling Indians actually
        // type: चीनी -> "chini", साबुन -> "sabun", तेल -> "tel", दूध -> "dudh"
        // are exact, and आटा -> "ata" is one edit from "atta". The vowel
        // length is lost, which costs nothing at all - phoneticKey discards
        // vowels entirely, and trigram similarity against a product name is
        // helped rather than hurt by the shorter form.
        m.put('अ', "a");   m.put('आ', "a");   m.put('इ', "i");   m.put('ई', "i");
        m.put('उ', "u");   m.put('ऊ', "u");   m.put('ऋ', "ri");  m.put('ए', "e");
        m.put('ऐ', "ai");  m.put('ओ', "o");   m.put('औ', "au");  m.put('ऑ', "o");
        m.put('ऍ', "e");

        // Dependent vowel signs (matras) - the same sounds, attached to a
        // consonant. Mapped identically to the independent forms above, which
        // is what makes "आटा" one word rather than two halves.
        m.put('ा', "a");   m.put('ि', "i");   m.put('ी', "i");   m.put('ु', "u");
        m.put('ू', "u");   m.put('ृ', "ri");  m.put('े', "e");   m.put('ै', "ai");
        m.put('ो', "o");   m.put('ौ', "au");  m.put('ॉ', "o");   m.put('ॅ', "e");

        // Consonants.
        m.put('क', "k");   m.put('ख', "kh");  m.put('ग', "g");   m.put('घ', "gh");
        m.put('ङ', "ng");
        m.put('च', "ch");  m.put('छ', "chh"); m.put('ज', "j");   m.put('झ', "jh");
        m.put('ञ', "ny");
        m.put('ट', "t");   m.put('ठ', "th");  m.put('ड', "d");   m.put('ढ', "dh");
        m.put('ण', "n");
        m.put('त', "t");   m.put('थ', "th");  m.put('द', "d");   m.put('ध', "dh");
        m.put('न', "n");
        m.put('प', "p");   m.put('फ', "ph");  m.put('ब', "b");   m.put('भ', "bh");
        m.put('म', "m");
        m.put('य', "y");   m.put('र', "r");   m.put('ल', "l");   m.put('व', "v");
        m.put('श', "sh");  m.put('ष', "sh");  m.put('स', "s");   m.put('ह', "h");
        m.put('ळ', "l");

        // Signs. The virama suppresses a vowel and the nukta is handled by
        // the lookahead in transliterate() - neither is a sound of its own
        // here. Anusvara and chandrabindu are nasals.
        m.put('्', "");    m.put('़', "");    m.put('ऽ', "");
        m.put('ं', "n");   m.put('ँ', "n");   m.put('ः', "h");

        // Devanagari digits - a size spoken in Hindi is still a size.
        m.put('०', "0");   m.put('१', "1");   m.put('२', "2");   m.put('३', "3");
        m.put('४', "4");   m.put('५', "5");   m.put('६', "6");   m.put('७', "7");
        m.put('८', "8");   m.put('९', "9");

        // Sentence punctuation, which normalize() would turn into a space
        // anyway - said explicitly so it never reaches a key.
        m.put('।', " ");   m.put('॥', " ");

        return Map.copyOf(m);
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
