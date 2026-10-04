package dev.senso.core.identity.internal.service;

import dev.senso.core.identity.internal.domain.User;
import dev.senso.core.identity.internal.domain.UserStatus;
import dev.senso.core.identity.internal.repository.UserRepository;
import dev.senso.core.identity.internal.security.JwtService;
import dev.senso.core.identity.internal.security.PasswordService;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationService {

    private final UserRepository userRepository;
    private final PasswordService passwordService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthenticationService(
        UserRepository userRepository,
        PasswordService passwordService,
        JwtService jwtService,
        RefreshTokenService refreshTokenService
    ) {
        this.userRepository = userRepository;
        this.passwordService = passwordService;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    public LoginResult login(
        String email,
        String rawPassword,
        String userAgent
    ) {

        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new IllegalStateException("Invalid credentials"));

        if (!passwordService.matches(rawPassword, user.getPasswordHash())) {
            throw new IllegalStateException("Invalid credentials");
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new IllegalStateException("User is not active");
        }

        String accessToken = jwtService.createAccessToken(user);

        String refreshToken = refreshTokenService.create(
            user.getId(),
            userAgent
        );

        return new LoginResult(
            accessToken,
            refreshToken
        );
    }
}
