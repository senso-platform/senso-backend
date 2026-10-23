package dev.senso.core.identity.api;

import java.util.Set;
import java.util.UUID;

public record UserInfo(UUID id, String email, String displayName, Set<String> roles) {}
