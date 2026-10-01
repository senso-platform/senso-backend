package identity.internal.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public class User {

    private UUID id;
    private String email;
    private String displayName;
    private String passwordHash;
    private Set<UserRole> roles;
    private Instant createdAt;

    public User(
        UUID id,
        String email,
        String displayName,
        String passwordHash,
        Set<UserRole> roles,
        Instant createdAt
    ) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.roles = roles;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Set<UserRole> getRoles() {
        return roles;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
