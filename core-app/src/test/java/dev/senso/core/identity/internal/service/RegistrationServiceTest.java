
package dev.senso.core.identity.internal.service;

import dev.senso.core.identity.internal.domain.User;
import dev.senso.core.identity.internal.domain.UserRole;
import dev.senso.core.identity.internal.domain.UserStatus;
import dev.senso.core.identity.internal.repository.UserRepository;
import dev.senso.core.identity.internal.security.PasswordService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordService passwordService;

    private RegistrationService registrationService;

    private final Instant fixedTime =
        Instant.parse("2026-10-08T12:00:00Z");

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
            fixedTime,
            java.time.ZoneOffset.UTC
        );

        registrationService = new RegistrationService(
            userRepository,
            passwordService,
            clock
        );
    }

    @Test
    void shouldRegisterUserSuccessfully() {
        // Arrange
        String email = "test@example.com";
        String displayName = "Test User";
        String password = "Password123!";

        when(userRepository.existsByEmail(email))
            .thenReturn(false);

        when(passwordService.hash(password))
            .thenReturn("hashedPassword");

        when(userRepository.save(any(User.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        User result = registrationService.register(
            email,
            displayName,
            password
        );

        // Assert
        assertNotNull(result);
        assertNotNull(result.getId());
        assertEquals(email, result.getEmail());
        assertEquals(displayName, result.getDisplayName());
        assertEquals("hashedPassword", result.getPasswordHash());
        assertEquals(UserStatus.ACTIVE, result.getStatus());
        assertEquals(Set.of(UserRole.USER), result.getRoles());
        assertEquals(fixedTime, result.getCreatedAt());
        assertEquals(fixedTime, result.getUpdatedAt());

        verify(passwordService).hash(password);
        verify(userRepository).save(any(User.class));
    }

    @Test
    void shouldRejectDuplicateEmail() {
        // Arrange
        String email = "test@example.com";

        when(userRepository.existsByEmail(email))
            .thenReturn(true);

        // Act + Assert
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> registrationService.register(
                email,
                "Test User",
                "Password123!"
            )
        );

        assertEquals("email exist", exception.getMessage());

        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(passwordService);
    }

    @Test
    void shouldHashPasswordBeforeSaving() {
        // Arrange
        String rawPassword = "Password123!";

        when(passwordService.hash(rawPassword))
            .thenReturn("secureHash");

        when(userRepository.save(any(User.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        registrationService.register(
            "test@example.com",
            "Test User",
            rawPassword
        );

        // Assert
        ArgumentCaptor<User> captor =
            ArgumentCaptor.forClass(User.class);

        verify(userRepository).save(captor.capture());

        User savedUser = captor.getValue();

        assertEquals("secureHash", savedUser.getPasswordHash());
        assertNotEquals(rawPassword, savedUser.getPasswordHash());

        verify(passwordService).hash(rawPassword);
    }

    @Test
    void shouldAssignDefaultRoleAndStatus() {
        // Arrange
        when(userRepository.save(any(User.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        User result = registrationService.register(
            "test@example.com",
            "Test User",
            "Password123!"
        );

        // Assert
        assertEquals(UserStatus.ACTIVE, result.getStatus());
        assertEquals(Set.of(UserRole.USER), result.getRoles());
    }
}
