package com.gpstore.ai;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, zero-cost fallback used when AI is disabled or unavailable.
 * It extracts only explicit constraints and known recipe components.
 */
@Component
public class FallbackAiProvider implements MarketplaceAiProvider {
    private static final Pattern BUDGET = Pattern.compile(
            "(?:under|below|within|budget|तक|से कम)\\s*(?:₹|rs\\.?|inr)?\\s*([0-9]{2,8})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PEOPLE = Pattern.compile(
            "(?:for|के लिए)\\s*([0-9]{1,3})\\s*(?:people|persons|लोग)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public Optional<Intent> interpret(String prompt) {
        if (prompt == null || prompt.isBlank()) return Optional.empty();
        String clean = prompt.trim().replaceAll("\\s+", " ");
        BigDecimal budget = number(BUDGET.matcher(clean));
        Integer quantity = integer(PEOPLE.matcher(clean));
        String lower = clean.toLowerCase(Locale.ROOT);
        List<String> required = new ArrayList<>();
        String query = conciseQuery(clean, lower);
        if (isBiryaniRequest(lower)) {
            if (lower.contains("ingredient") || lower.contains("groceries")
                    || lower.contains("shopping list") || lower.contains("सामान")) {
                required.addAll(List.of("basmati rice", "onion", "tomato", "biryani masala",
                        "curd", "cooking oil"));
            }
            query = "biryani";
        } else if (lower.contains("paneer butter masala") || lower.contains("पनीर बटर मसाला")) {
            required.addAll(List.of("paneer", "butter", "tomato", "cream", "garam masala"));
            query = "paneer butter masala";
        } else if ((lower.contains("cake") && lower.contains("flower"))
                || (lower.contains("केक") && lower.contains("फूल"))) {
            required.addAll(List.of("birthday cake", "flowers"));
            query = "birthday";
        }
        String mode = lower.contains("repair") || lower.contains("मरम्मत")
                ? "SERVICE_AT_SHOP" : null;
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        for (String colour : List.of("red", "blue", "black", "white", "लाल", "नीला")) {
            if (lower.contains(colour)) attributes.put("color", colour);
        }
        return Optional.of(new Intent(query, null, budget, quantity, mode,
                attributes, List.copyOf(required), detectLanguage(clean)));
    }

    @Override
    public String name() {
        return "FALLBACK";
    }

    private static BigDecimal number(Matcher matcher) {
        return matcher.find() ? new BigDecimal(matcher.group(1)) : null;
    }

    private static Integer integer(Matcher matcher) {
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private static boolean isBiryaniRequest(String lower) {
        return lower.contains("biryani") || lower.contains("briyani")
                || lower.contains("biriyani") || lower.contains("biriani")
                || lower.contains("बिरयानी");
    }

    private static String detectLanguage(String value) {
        boolean hindi = value.codePoints().anyMatch(c -> c >= 0x0900 && c <= 0x097f);
        boolean latin = value.codePoints().anyMatch(c -> c < 128 && Character.isLetter(c));
        return hindi && latin ? "HINGLISH" : hindi ? "HINDI" : "ENGLISH";
    }

    private static String conciseQuery(String original, String lower) {
        if (lower.contains("repair") || lower.contains("मरम्मत")) {
            if (lower.contains("iphone")) return "iPhone repair";
            if (lower.contains("mobile") || lower.contains("phone")) return "mobile repair";
        }
        String query = BUDGET.matcher(original).replaceAll(" ");
        query = PEOPLE.matcher(query).replaceAll(" ");
        query = query.replaceAll(
                "(?i)\\b(find|show|suggest|recommend|need|want|please|available|availability|where|can|i|me|my|near|nearby|locally|a|an|the)\\b",
                " ");
        query = query.replaceAll(
                "(?i)\\b(mujhe|chahiye|dikhao|paas|aas paas|ke liye)\\b", " ");
        query = query.replaceAll("[?.,!]+", " ").replaceAll("\\s+", " ").trim();
        return query.isBlank() ? original : query;
    }
}
