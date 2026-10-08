
package dev.senso.core.identity.internal.service;

import dev.senso.core.identity.internal.security.PasswordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordServiceTest {

    private PasswordService passwordService;

    @BeforeEach
    void setUp() {
        passwordService = new PasswordService();
    }

    @Test
    void shouldHashPassword() {
        String rawPassword = "Password123!";

        String hash = passwordService.hash(rawPassword);

        assertNotNull(hash);
        assertNotEquals(rawPassword, hash);
        assertTrue(hash.startsWith("$2"));
    }

    @Test
    void shouldMatchCorrectPassword() {
        String rawPassword = "Password123!";
        String hash = passwordService.hash(rawPassword);

        boolean result = passwordService.matches(
            rawPassword,
            hash
        );

        assertTrue(result);
    }

    @Test
    void shouldRejectIncorrectPassword() {
        String hash = passwordService.hash("Password123!");

        boolean result = passwordService.matches(
            "WrongPassword",
            hash
        );

        assertFalse(result);
    }

    @Test
    void shouldGenerateDifferentHashesForSamePassword() {
        String rawPassword = "Password123!";

        String firstHash = passwordService.hash(rawPassword);
        String secondHash = passwordService.hash(rawPassword);

        assertNotEquals(firstHash, secondHash);

        assertTrue(passwordService.matches(rawPassword, firstHash));
        assertTrue(passwordService.matches(rawPassword, secondHash));
    }
}
