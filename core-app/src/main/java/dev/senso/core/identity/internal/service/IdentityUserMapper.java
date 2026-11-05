package dev.senso.core.identity.internal.service;

import dev.senso.core.identity.api.UserInfo;
import dev.senso.core.identity.internal.domain.User;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface IdentityUserMapper {

    UserInfo toInfo(User user);
}
