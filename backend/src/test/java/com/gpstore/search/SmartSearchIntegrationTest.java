package com.gpstore.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.gpstore.dto.response.ProductResponse;
import com.gpstore.entity.Category;
import com.gpstore.entity.Product;
import com.gpstore.entity.ProductVariant;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.ProductRepository;
import com.gpstore.repository.ProductVariantRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;

/**
 * Smart Search against the real database and the real search stack -
 * pg_trgm, the synonym table seeded by V12, and the phonetic layer together.
 *
 * <p>Written against products created here with unlikely names, so the
 * assertions are about THIS catalogue rather than about whatever else the
 * shared test database happens to contain.
 */
@SpringBootTest(properties = {
        "outbox.initial-delay-ms=3600000",
        "outbox.drain-interval-ms=3600000",
        "payment.expiry-initial-delay-ms=3600000",
        "idempotency.cleanup-initial-delay-ms=3600000",
        "otp.cleanup-initial-delay-ms=3600000",
        "delivery.late-flag-initial-delay-ms=3600000"
})
class SmartSearchIntegrationTest {

    @Autowired private SmartSearchService smartSearch;
    @Autowired private SynonymDictionary synonyms;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private CacheManager cacheManager;

    /** Unique per run so a rerun does not collide with its own leftovers. */
    private String marker;
    private Category category;
    private final List<Long> createdVariantIds = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        createdVariantIds.clear();
        marker = "zqx" + UUID.randomUUID().toString().substring(0, 6);

        category = new Category();
        category.setName("Smart search " + marker);
        category.setActive(true);
        category = categoryRepository.save(category);

        create("Sugar " + marker, "Tata");
        create("Turmeric Powder " + marker, "Everest");
        create("Milk " + marker, "Amul");
        create("Rice " + marker, "India Gate");
        create("Salt " + marker, "Tata");
        create("Aashirvaad Atta " + marker, "Aashirvaad");
        create("Soap " + marker, "Lifebuoy");

        clearSearchCache();
    }

    @AfterEach
    void tearDown() {
        // Variants first - they hold the foreign key to the product.
        createdVariantIds.forEach(variantRepository::deleteById);
        createdVariantIds.clear();

        List<Product> mine = productRepository.findAll().stream()
                .filter(p -> p.getName() != null && p.getName().contains(marker))
                .toList();
        productRepository.deleteAll(mine);
        categoryRepository.delete(category);
        clearSearchCache();
    }

    private void create(String name, String brand) {
        Product product = new Product();
        product.setName(name);
        product.setBrand(brand);
        product.setCategory(category);
        product.setActive(true);
        Product saved = productRepository.save(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(saved);
        variant.setQuantity(1.0);
        variant.setUnit("kg");
        variant.setSellingPrice(BigDecimal.valueOf(50));
        variant.setAvailable(true);
        createdVariantIds.add(variantRepository.save(variant).getId());
    }

    private void clearSearchCache() {
        // searchInstant is cached on keyword+page; without this, a result
        // cached by a previous test would be served instead of a real query.
        var cache = cacheManager.getCache("productSearch");
        if (cache != null) cache.clear();
    }

    private List<ProductResponse> find(String query) {
        return smartSearch.search(query + " " + marker, PageRequest.of(0, 20)).getResults().getContent();
    }

    private SmartSearchResult searchRaw(String query) {
        return smartSearch.search(query + " " + marker, PageRequest.of(0, 20));
    }

    private void findsProductNamed(String query, String expectedWord) {
        assertThat(find(query))
                .as("searching '%s' should find a product named like '%s'", query, expectedWord)
                .isNotEmpty()
                .anySatisfy(p -> assertThat(p.getName().toLowerCase()).contains(expectedWord));
    }

    @Test
    @DisplayName("the vocabulary is present without migrations having run")
    void vocabularyIsSelfSeeding() {
        // THE REGRESSION THIS EXISTS FOR. The vocabulary used to be seeded by
        // the V12 migration alone, and CI builds its schema from the JPA
        // entities with Flyway disabled - so the table existed and was empty
        // there, every Hindi word silently stopped translating, and nothing
        // failed loudly enough to say why. Seeding from the application
        // covers every environment; this asserts it actually happened.
        assertThat(synonyms.size())
                .as("the bundled vocabulary should be loaded in any environment")
                .isGreaterThan(50);

        // Through the phonetic key, so this also covers spellings that are
        // not in the file at all.
        assertThat(synonyms.canonicalFor("cheeni")).contains("sugar");
        assertThat(synonyms.canonicalFor("doodh")).contains("milk");
    }

    @Test
    @DisplayName("a word the dictionary does not know is left alone")
    void unknownWordsAreNotTranslated() {
        // "no opinion" has to stay distinguishable from "translate this",
        // otherwise every English product name would be rewritten.
        assertThat(synonyms.canonicalFor("britannia")).isEmpty();
        assertThat(synonyms.canonicalFor("zzzz")).isEmpty();
    }

    @Test
    @DisplayName("an exactly-typed search still works")
    void exactMatchStillWorks() {
        // The regression that would matter most: Smart Search must not make
        // ordinary correct searches worse.
        findsProductNamed("sugar", "sugar");
        findsProductNamed("milk", "milk");
        findsProductNamed("rice", "rice");
        findsProductNamed("salt", "salt");
    }

    @Test
    @DisplayName("misspellings find the product")
    void typos() {
        findsProductNamed("sogar", "sugar");
        findsProductNamed("sugr", "sugar");
        findsProductNamed("turmerc", "turmeric");
        findsProductNamed("solt", "salt");
    }

    @Test
    @DisplayName("Hindi and Hinglish words find the English product")
    void hinglish() {
        findsProductNamed("chini", "sugar");
        findsProductNamed("cheeni", "sugar");
        findsProductNamed("haldi", "turmeric");
        findsProductNamed("haldhi", "turmeric");
        findsProductNamed("doodh", "milk");
        findsProductNamed("dudh", "milk");
        findsProductNamed("chawal", "rice");
        findsProductNamed("chaval", "rice");
        findsProductNamed("namak", "salt");
        findsProductNamed("sabun", "soap");
    }

    @Test
    @DisplayName("a misspelled brand still finds the brand's product")
    void misspelledBrand() {
        findsProductNamed("ashirvad", "atta");
        findsProductNamed("ashirwad", "atta");
    }

    @Test
    @DisplayName("a quantity in the query does not stop the product being found")
    void quantityIsDroppable() {
        // "chini 1 kilo" - the quantity is real to the customer but is not
        // part of the product's name.
        assertThat(smartSearch.search("chini 1 kilo " + marker, PageRequest.of(0, 20)).getResults().getContent())
                .as("a quantity in the query must not make the product unfindable")
                .isNotEmpty();
    }

    @Test
    @DisplayName("a correct search reports no correction")
    void noCorrectionWhenNothingIsWrong() {
        // Aggressively "correcting" a query that was already right is worse
        // than not correcting at all.
        SmartSearchResult result = searchRaw("sugar");
        assertThat(result.getResults().getContent()).isNotEmpty();
        assertThat(result.getDidYouMean()).isNull();
    }

    @Test
    @DisplayName("a Hinglish search reports what it searched for instead")
    void translationIsReported() {
        SmartSearchResult result = searchRaw("chini");
        assertThat(result.getResults().getContent()).isNotEmpty();

        // Either layer may have found it - what matters is that the customer
        // is told the search ran on "sugar" rather than silently swapping it.
        String reported = result.getInterpretedAs() != null ? result.getInterpretedAs() : result.getDidYouMean();
        assertThat(reported)
                .as("the customer must be able to see that 'chini' was read as 'sugar'")
                .isNotNull()
                .containsIgnoringCase("sugar");
    }

    // ------------------------------------------------------------ Devanagari
    //
    // Voice made these load-bearing. An Android recogniser set to hi-IN
    // returns Hindi script for everything, so a customer speaking Hindi
    // produces a query that, before transliteration, could not have matched a
    // single product in a catalogue written in Latin.
    //
    // NOTE ON THE MISSING MARKER. Every other test here appends a unique
    // marker so it only ever asserts about products it created. These
    // deliberately do not: the marker is Latin, and searching "आटा MARKER"
    // would match all seven fixtures on the marker alone - the test would
    // pass whether or not the Devanagari half did anything at all. Searching
    // the Hindi word by itself is the only way to prove the script reached
    // the catalogue.

    @Test
    @DisplayName("a brand spoken in Hindi script reaches the brand in the catalogue")
    void devanagariBrandIsFound() {
        // The case the dictionary cannot help with, and the reason the
        // romanised layer exists at all. "आशीर्वाद" is in no vocabulary and
        // never will be - brands are catalogue data, not words - so the only
        // route from Hindi script to this product is transliteration.
        clearSearchCache();
        List<ProductResponse> results =
                smartSearch.search("आशीर्वाद", PageRequest.of(0, 20)).getResults().getContent();

        assertThat(results)
                .as("searching the brand in Hindi script must find the brand")
                .isNotEmpty()
                .anySatisfy(product -> assertThat(
                        (product.getName() + " " + product.getBrand()).toLowerCase())
                        .contains("aashirvaad"));
    }

    @Test
    @DisplayName("a Hindi-script search says what it searched for instead")
    void devanagariIsReported() {
        clearSearchCache();
        SmartSearchResult result = smartSearch.search("आशीर्वाद", PageRequest.of(0, 20));

        assertThat(result.getInterpretedAs())
                .as("a customer who spoke Hindi must be able to see what the shop searched for - "
                        + "results they cannot explain are results they cannot correct")
                .isNotNull()
                // The consonant core, not a chosen vowel spelling: what the
                // customer must see is that the shop searched for their
                // brand, and "shirvad" is the part of that no transliteration
                // scheme can move.
                .containsIgnoringCase("shirvad");
    }

    @Test
    @DisplayName("a Hindi grocery word still goes through the vocabulary, not the romaniser")
    void devanagariWordUsesTheDictionary() {
        // The other half. "चीनी" IS in the vocabulary, and the right answer is
        // Sugar - not a romanised "cheenee", which matches nothing. This
        // passes only because normalize() now transliterates before building
        // the phonetic key; before that, the Hindi spelling produced a key of
        // Devanagari characters that could never equal the key of "chini".
        assertThat(SearchNormalizer.phoneticKey("चीनी"))
                .as("Hindi and Hinglish spellings of the same word must key alike")
                .isEqualTo(SearchNormalizer.phoneticKey("chini"))
                .isNotEmpty();

        assertThat(synonyms.canonicalFor("चीनी"))
                .as("चीनी must resolve to the same canonical word as chini")
                .isEqualTo(synonyms.canonicalFor("chini"));
    }

    @Test
    @DisplayName("nonsense returns nothing rather than unrelated products")
    void nonsenseFindsNothing() {
        // The rule that keeps search trustworthy: no random products just
        // because they share a letter.
        SmartSearchResult result = smartSearch.search("qwertyuiopzxcv", PageRequest.of(0, 20));
        assertThat(result.getResults().getContent()).isEmpty();
        assertThat(result.getInterpretedAs()).isNull();
        assertThat(result.getDidYouMean()).isNull();
    }

    @Test
    @DisplayName("a blank search is rejected, as it was before")
    void blankRejected() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                com.gpstore.exception.BadRequestException.class,
                () -> smartSearch.search("   ", PageRequest.of(0, 20)))).isNotNull();
    }
}
