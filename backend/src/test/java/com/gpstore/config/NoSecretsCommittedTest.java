package com.gpstore.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Operator credentials live in VPS env / GitHub secrets, not in git.
 * This pins that the committed example file is empty or CHANGE_ME.
 */
class NoSecretsCommittedTest {

    @Test
    void envExampleDoesNotContainLiveProviderSecrets() throws IOException {
        Path example = Path.of(".env.example");
        if (!Files.exists(example)) {
            example = Path.of("backend/.env.example");
        }
        assertTrue(Files.exists(example), "backend/.env.example must exist");
        List<String> lines = Files.readAllLines(example);
        String joined = String.join("\n", lines).toLowerCase(Locale.ROOT);

        assertFalse(joined.contains("sk_live_"), "Cashfree/Stripe live secret in .env.example");
        assertFalse(joined.contains("begin private key"), "private key material in .env.example");
        assertFalse(joined.contains("akia"), "AWS-looking key in .env.example");

        assertEqualsEmptyOrPlaceholder(lines, "CASHFREE_SECRET_KEY");
        assertEqualsEmptyOrPlaceholder(lines, "CASHFREE_APP_ID");
        assertEqualsEmptyOrPlaceholder(lines, "CASHFREE_WEBHOOK_SECRET");
        assertEqualsEmptyOrPlaceholder(lines, "MSG91_AUTH_KEY");
        assertEqualsEmptyOrPlaceholder(lines, "FIREBASE_CREDENTIALS_BASE64");
        assertEqualsEmptyOrPlaceholder(lines, "CLOUDINARY_API_SECRET");
        assertEqualsEmptyOrPlaceholder(lines, "R2_SECRET_ACCESS_KEY");
        assertEqualsEmptyOrPlaceholder(lines, "R2_ACCESS_KEY_ID");
        assertEqualsEmptyOrPlaceholder(lines, "R2_ACCOUNT_ID");
        assertEqualsEmptyOrPlaceholder(lines, "STORE_SUPPORT_PHONE");
        assertEqualsEmptyOrPlaceholder(lines, "STORE_SUPPORT_EMAIL");
    }

    @Test
    void flutterLibDoesNotContainStorageSecretsOrCloudinaryUploadClient() throws IOException {
        Path lib = Path.of("frontend/lib");
        if (!Files.isDirectory(lib)) {
            lib = Path.of("../frontend/lib");
        }
        assertTrue(Files.isDirectory(lib), "frontend/lib must exist");
        StringBuilder all = new StringBuilder();
        try (var walk = Files.walk(lib)) {
            walk.filter(p -> p.toString().endsWith(".dart")).forEach(p -> {
                try {
                    all.append(Files.readString(p)).append('\n');
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        String text = all.toString();
        assertFalse(text.contains("R2_SECRET_ACCESS_KEY"));
        assertFalse(text.contains("R2_ACCESS_KEY_ID"));
        assertFalse(text.contains("AWS_SECRET_ACCESS_KEY"));
        assertFalse(text.contains("CLOUDINARY_API_SECRET"));
        assertFalse(text.contains("api.cloudinary.com"));
        assertFalse(text.contains("cloudinary-signature"));
        assertFalse(text.contains("getCloudinarySignature"));
        assertFalse(text.contains("CloudinarySignature"));
        assertTrue(text.contains("ImageUploadService"),
                "Flutter must upload through ImageUploadService, not a storage SDK");
    }

    private static void assertEqualsEmptyOrPlaceholder(List<String> lines, String key) {
        String value = "";
        for (String line : lines) {
            if (line.startsWith(key + "=")) {
                value = line.substring(key.length() + 1).trim();
                break;
            }
        }
        assertTrue(value.isEmpty()
                        || value.contains("CHANGE_ME")
                        || value.equals("false")
                        || PlaceholderValues.isSecretPlaceholder(value)
                        || PlaceholderValues.isBlankOrPlaceholder(value),
                key + " must be empty or a placeholder in .env.example, was: " + value);
    }
}
