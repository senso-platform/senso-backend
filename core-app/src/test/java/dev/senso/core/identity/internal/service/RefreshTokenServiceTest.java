
package dev.senso.core.identity.internal.service;

import dev.senso.core.identity.internal.domain.RefreshToken;
import dev.senso.core.identity.internal.repository.RefreshTokenRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private RefreshTokenService refreshTokenService;

    private static final Instant NOW =
        Instant.parse("2026-10-08T12:00:00Z");

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        refreshTokenService = new RefreshTokenService(
            refreshTokenRepository,
            clock
        );
    }

    @Test
    void shouldCreateRefreshToken() {
        // Act
        String token = refreshTokenService.create(
            userId,
            "Chrome"
        );

        // Assert
        assertNotNull(token);
        assertFalse(token.isBlank());

        verify(refreshTokenRepository).save(
            any(RefreshToken.class)
        );
    }

    @Test
    void shouldGenerateDifferentTokens() {
        // Act
        String firstToken =
            refreshTokenService.create(userId, "Chrome");

        String secondToken =
            refreshTokenService.create(userId, "Chrome");

        // Assert
        assertNotEquals(firstToken, secondToken);

        verify(refreshTokenRepository, times(2))
            .save(any(RefreshToken.class));
    }

    @Test
    void shouldGenerateSecureRandomToken() {
        // Act
        String token =
            refreshTokenService.create(userId, "Chrome");

        // Assert
        byte[] decoded = Base64.getUrlDecoder().decode(token);

        assertEquals(32, decoded.length);
        assertFalse(token.contains("="));
    }

    @Test
    void shouldStoreHashedToken() throws Exception {
        // Arrange
        ArgumentCaptor<RefreshToken> captor =
            ArgumentCaptor.forClass(RefreshToken.class);

        // Act
        String rawToken =
            refreshTokenService.create(userId, "Chrome");

        // Assert
        verify(refreshTokenRepository)
            .save(captor.capture());

        RefreshToken savedToken = captor.getValue();

        byte[] storedHash = (byte[]) ReflectionTestUtils.getField(
            savedToken,
            "tokenHash"
        );

        byte[] expectedHash = MessageDigest
            .getInstance("SHA-256")
            .digest(rawToken.getBytes(StandardCharsets.UTF_8));

        assertNotNull(storedHash);
        assertArrayEquals(expectedHash, storedHash);
        assertEquals(32, storedHash.length);
    }

    @Test
    void shouldSaveCorrectTokenMetadata() {
        // Arrange
        ArgumentCaptor<RefreshToken> captor =
            ArgumentCaptor.forClass(RefreshToken.class);

        // Act
        refreshTokenService.create(userId, "Chrome");

        // Assert
        verify(refreshTokenRepository)
            .save(captor.capture());

        RefreshToken savedToken = captor.getValue();

        assertNotNull(
            ReflectionTestUtils.getField(savedToken, "id")
        );

        assertEquals(
            userId,
            ReflectionTestUtils.getField(savedToken, "userId")
        );

        assertNotNull(
            ReflectionTestUtils.getField(savedToken, "familyId")
        );

        assertEquals(
            NOW,
            ReflectionTestUtils.getField(savedToken, "createdAt")
        );

        assertEquals(
            NOW.plus(7, ChronoUnit.DAYS),
            ReflectionTestUtils.getField(savedToken, "expiresAt")
        );

        assertEquals(
            "Chrome",
            ReflectionTestUtils.getField(savedToken, "userAgent")
        );

        assertNull(
            ReflectionTestUtils.getField(savedToken, "usedAt")
        );

        assertNull(
            ReflectionTestUtils.getField(savedToken, "revokedAt")
        );
    }
}
