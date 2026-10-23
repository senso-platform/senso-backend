package dev.senso.core.identity.api;

import java.util.Optional;
import java.util.UUID;

public interface IdentityApi {

    Optional<UserInfo> findUserById(UUID userId);

    boolean existsById(UUID userId);
}
