package dev.senso.core.identity.internal.service;

import dev.senso.core.identity.internal.domain.RefreshToken;
import dev.senso.core.identity.internal.repository.RefreshTokenRepository;
import dev.senso.kernel.id.Ids;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(
        RefreshTokenRepository refreshTokenRepository,
        Clock clock
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
    }

    public String create(UUID userId, String userAgent) {

        String rawToken = generateToken();
        byte[] tokenHash = hash(rawToken);

        Instant now = clock.instant();

        RefreshToken refreshToken = new RefreshToken(
            Ids.newId(),
            userId,
            Ids.newId(),
            tokenHash,
            now.plus(7, ChronoUnit.DAYS),
            now,
            userAgent
        );

        refreshTokenRepository.save(refreshToken);

        return rawToken;
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes);
    }

    private byte[] hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(token.getBytes());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash refresh token", e);
        }
    }
}
