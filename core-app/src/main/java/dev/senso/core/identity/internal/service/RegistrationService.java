package dev.senso.core.identity.internal.service;


import dev.senso.core.identity.internal.domain.User;
import dev.senso.core.identity.internal.domain.UserRole;
import dev.senso.core.identity.internal.domain.UserStatus;
import dev.senso.core.identity.internal.repository.UserRepository;
import dev.senso.core.identity.internal.security.PasswordService;
import org.springframework.stereotype.Service;
import dev.senso.kernel.id.Ids;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;


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

    public User register(String email, String displayName, String rawPassword) {
        if(userRepository.existsByEmail(email)){
            throw new IllegalStateException("email exist");
        }

        Instant now = clock.instant();

        User user = new User(
            Ids.newId(),
            email,
            displayName,
            passwordService.hash(rawPassword),
            UserStatus.ACTIVE,
            now,
            now,
            Set.of(UserRole.USER)
        );

        return userRepository.save(user);

    }

}
