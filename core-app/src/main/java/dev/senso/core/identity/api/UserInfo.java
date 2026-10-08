package dev.senso.core.identity.api;

import java.util.UUID;
import java.util.Set;

public record UserInfo(
    UUID id,
    String email,
    String displayName,
    Set<String> roles
) {}
