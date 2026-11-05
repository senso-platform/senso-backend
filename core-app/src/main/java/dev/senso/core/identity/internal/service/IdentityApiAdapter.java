package dev.senso.core.identity.internal.service;

import dev.senso.core.identity.api.IdentityApi;
import dev.senso.core.identity.api.UserInfo;
import dev.senso.core.identity.internal.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityApiAdapter implements IdentityApi {

    private final UserRepository users;
    private final IdentityUserMapper mapper;

    public IdentityApiAdapter(UserRepository users, IdentityUserMapper mapper) {
        this.users = users;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserInfo> findUserById(UUID userId) {
        return users.findById(userId).map(mapper::toInfo);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsById(UUID userId) {
        return users.existsById(userId);
    }
}
