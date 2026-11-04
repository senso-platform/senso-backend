package dev.senso.core.identity.internal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EmailNormalizerTest {

    @Test
    void shouldConvertEmailToLowerCase() {
        assertEquals("test@example.com", EmailNormalizer.normalizeEmail("TEST@EXAMPLE.COM"));
    }

    @Test
    void shouldRemoveSurroundingSpaces() {
        assertEquals("test@example.com", EmailNormalizer.normalizeEmail("  test@example.com  "));
    }

    @Test
    void shouldNormalizeMixedCaseEmail() {
        assertEquals("test@example.com", EmailNormalizer.normalizeEmail("  Test@Example.Com  "));
    }
}
