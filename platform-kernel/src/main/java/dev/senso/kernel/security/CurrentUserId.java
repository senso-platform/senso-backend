package dev.senso.kernel.security;

import java.util.Optional;
import java.util.UUID;

/** Access to the authenticated user id; implemented in core-app (core.shared.security). */
public interface CurrentUserId {

    Optional<UUID> currentUserId();
}
