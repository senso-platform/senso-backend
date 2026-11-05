package dev.senso.core.identity.internal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import dev.senso.core.identity.api.UserInfo;
import dev.senso.core.identity.internal.domain.User;
import dev.senso.core.identity.internal.domain.UserRole;
import dev.senso.core.identity.internal.domain.UserStatus;
import dev.senso.core.identity.internal.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdentityApiAdapterTest {

    private static final UUID USER_ID = UUID.fromString("019a4c11-14e0-7000-8000-000000000001");

    @Mock
    private UserRepository users;

    private IdentityApiAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new IdentityApiAdapter(users, Mappers.getMapper(IdentityUserMapper.class));
    }

    @Test
    void returnsPublicUserInformation() {
        Instant createdAt = Instant.parse("2026-11-05T09:00:00Z");
        User user = new User(
                USER_ID,
                "owner@example.com",
                "Owner",
                "private-password-hash",
                UserStatus.ACTIVE,
                createdAt,
                createdAt,
                Set.of(UserRole.USER, UserRole.ADMIN));
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));

        assertEquals(
                Optional.of(new UserInfo(USER_ID, "owner@example.com", "Owner", Set.of("USER", "ADMIN"))),
                adapter.findUserById(USER_ID));
    }

    @Test
    void returnsEmptyForUnknownUser() {
        when(users.findById(USER_ID)).thenReturn(Optional.empty());

        assertTrue(adapter.findUserById(USER_ID).isEmpty());
    }

    @Test
    void reportsWhetherUserExists() {
        when(users.existsById(USER_ID)).thenReturn(true, false);

        assertTrue(adapter.existsById(USER_ID));
        assertFalse(adapter.existsById(USER_ID));
    }
}
