package dev.senso.core.identity.internal.service;

import java.util.Locale;

public final class EmailNormalizer {

    private EmailNormalizer() {
        // Utility class
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
