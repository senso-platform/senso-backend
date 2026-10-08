
package dev.senso.core.identity.internal.service;

import dev.senso.core.identity.internal.domain.User;
import dev.senso.core.identity.internal.domain.UserRole;
import dev.senso.core.identity.internal.domain.UserStatus;
import dev.senso.core.identity.internal.repository.UserRepository;
import dev.senso.core.identity.internal.security.JwtService;
import dev.senso.core.identity.internal.security.PasswordService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordService passwordService;

    @Mock
    private JwtService jwtService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private AuthenticationService authenticationService;

    private User user;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();

        user = new User(
            UUID.randomUUID(),
            "test@example.com",
            "Test User",
            "hashedPassword",
            UserStatus.ACTIVE,
            now,
            now,
            Set.of(UserRole.USER)
        );
    }

    @Test
    void shouldLoginSuccessfully() {
        // Arrange
        when(userRepository.findByEmail("test@example.com"))
            .thenReturn(Optional.of(user));

        when(passwordService.matches(
            "Password123!", "hashedPassword"
        )).thenReturn(true);

        when(jwtService.createAccessToken(user))
            .thenReturn("access-token");

        when(refreshTokenService.create(user.getId(), "Chrome"))
            .thenReturn("refresh-token");

        // Act
        LoginResult result = authenticationService.login(
            "test@example.com",
            "Password123!",
            "Chrome"
        );

        // Assert
        assertNotNull(result);

        verify(jwtService).createAccessToken(user);
        verify(refreshTokenService).create(user.getId(), "Chrome");
    }

    @Test
    void shouldRejectUnknownUser() {
        // Arrange
        when(userRepository.findByEmail("wrong@example.com"))
            .thenReturn(Optional.empty());

        // Act + Assert
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> authenticationService.login(
                "wrong@example.com",
                "Password123!",
                "Chrome"
            )
        );

        assertEquals("Invalid credentials", exception.getMessage());

        verifyNoInteractions(
            passwordService,
            jwtService,
            refreshTokenService
        );
    }

    @Test
    void shouldRejectIncorrectPassword() {
        // Arrange
        when(userRepository.findByEmail("test@example.com"))
            .thenReturn(Optional.of(user));

        when(passwordService.matches(
            "WrongPassword", "hashedPassword"
        )).thenReturn(false);

        // Act + Assert
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> authenticationService.login(
                "test@example.com",
                "WrongPassword",
                "Chrome"
            )
        );

        assertEquals("Invalid credentials", exception.getMessage());

        verifyNoInteractions(jwtService, refreshTokenService);
    }

    @Test
    void shouldRejectInactiveUser() {
        // Arrange
        Instant now = Instant.now();

        User inactiveUser = new User(
            UUID.randomUUID(),
            "inactive@example.com",
            "Inactive User",
            "hashedPassword",
            null,
            now,
            now,
            Set.of(UserRole.USER)
        );

        when(userRepository.findByEmail("inactive@example.com"))
            .thenReturn(Optional.of(inactiveUser));

        when(passwordService.matches(
            "Password123!", "hashedPassword"
        )).thenReturn(true);

        // Act + Assert
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> authenticationService.login(
                "inactive@example.com",
                "Password123!",
                "Chrome"
            )
        );

        assertEquals("User is not active", exception.getMessage());

        verifyNoInteractions(jwtService, refreshTokenService);
    }
}
