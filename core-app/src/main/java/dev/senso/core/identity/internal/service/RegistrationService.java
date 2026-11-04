package dev.senso.core.identity.internal.service;

import dev.senso.core.identity.internal.domain.User;
import dev.senso.core.identity.internal.domain.UserRole;
import dev.senso.core.identity.internal.domain.UserStatus;
import dev.senso.core.identity.internal.repository.UserRepository;
import dev.senso.core.identity.internal.security.PasswordService;
import dev.senso.kernel.error.ConflictException;
import dev.senso.kernel.id.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

    private final UserRepository userRepository;
    private final PasswordService passwordService;
    private final Clock clock;

    public RegistrationService(UserRepository userRepository, PasswordService passwordService, Clock clock) {
        this.userRepository = userRepository;
        this.passwordService = passwordService;
        this.clock = clock;
    }

    @Transactional
    public User register(String email, String displayName, String rawPassword) {

        String normalizedEmail = EmailNormalizer.normalizeEmail(email);

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new ConflictException("Email is already registered");
        }

        Instant now = clock.instant();

        User user = new User(
                Ids.newId(),
                normalizedEmail,
                displayName,
                passwordService.hash(rawPassword),
                UserStatus.ACTIVE,
                now,
                now,
                Set.of(UserRole.USER));

        return userRepository.save(user);
    }
}
