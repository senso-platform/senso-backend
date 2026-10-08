
package dev.senso.core.identity.internal.service;

import com.nimbusds.jose.JWSAlgorithm;
import dev.senso.core.identity.internal.domain.User;
import dev.senso.core.identity.internal.domain.UserRole;
import dev.senso.core.identity.internal.domain.UserStatus;

import dev.senso.core.identity.internal.security.JwtKeyProvider;
import dev.senso.core.identity.internal.security.JwtService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private static final Instant NOW =
        Instant.parse("2026-10-08T12:00:00Z");

    private static JwtKeyProvider keyProvider;
    private static JwtService jwtService;
    private static NimbusJwtDecoder jwtDecoder;
    private static User user;

    @BeforeAll
    static void setUp() {
        keyProvider = new JwtKeyProvider();

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        jwtService = new JwtService(keyProvider, clock);


        jwtDecoder = NimbusJwtDecoder
            .withPublicKey(keyProvider.getPublicKey())
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();

        JwtTimestampValidator validator =
            new JwtTimestampValidator(Duration.ZERO);

        validator.setClock(
            Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC)
        );

        jwtDecoder.setJwtValidator(validator);


        user = new User(
            UUID.randomUUID(),
            "test@example.com",
            "Test User",
            "hashedPassword",
            UserStatus.ACTIVE,
            NOW,
            NOW,
            Set.of(UserRole.USER)
        );
    }

    @Test
    void shouldCreateAccessToken() {
        String token = jwtService.createAccessToken(user);

        assertNotNull(token);
        assertEquals(3, token.split("\\.").length);
    }

    @Test
    void shouldContainCorrectClaims() {
        String token = jwtService.createAccessToken(user);

        Jwt jwt = jwtDecoder.decode(token);

        assertEquals(user.getId().toString(), jwt.getSubject());
        assertEquals(NOW, jwt.getIssuedAt());
        assertEquals(NOW.plusSeconds(900), jwt.getExpiresAt());

        List<String> roles = jwt.getClaimAsStringList("roles");

        assertNotNull(roles);
        assertTrue(roles.contains("USER"));
    }

    @Test
    void shouldRejectTamperedToken() {
        String token = jwtService.createAccessToken(user);

        String[] parts = token.split("\\.");

        // Меняем один символ подписи, сохраняя её формат.
        char first = parts[2].charAt(0);
        char replacement = first == 'A' ? 'B' : 'A';

        parts[2] = replacement + parts[2].substring(1);

        String tamperedToken = String.join(".", parts);

        assertThrows(
            JwtException.class,
            () -> jwtDecoder.decode(tamperedToken)
        );
    }


    @Test
    void shouldRejectExpiredToken() {
        // Arrange
        String token = jwtService.createAccessToken(user);

        Instant later = NOW.plusSeconds(901);

        NimbusJwtDecoder expiredDecoder = NimbusJwtDecoder
            .withPublicKey(keyProvider.getPublicKey())
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();

        JwtTimestampValidator validator =
            new JwtTimestampValidator(Duration.ZERO);

        validator.setClock(
            Clock.fixed(later, ZoneOffset.UTC)
        );

        expiredDecoder.setJwtValidator(validator);

        // Act + Assert
        assertThrows(
            JwtException.class,
            () -> expiredDecoder.decode(token)
        );
    }


    @Test
    void shouldRejectTokenSignedWithAnotherKey() {
        String token = jwtService.createAccessToken(user);

        JwtKeyProvider otherKeyProvider = new JwtKeyProvider();

        NimbusJwtDecoder otherDecoder =
            NimbusJwtDecoder
                .withPublicKey(otherKeyProvider.getPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();

        assertThrows(
            JwtException.class,
            () -> otherDecoder.decode(token)
        );
    }
}
