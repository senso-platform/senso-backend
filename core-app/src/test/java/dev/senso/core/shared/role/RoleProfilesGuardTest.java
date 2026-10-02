package dev.senso.core.shared.role;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RoleProfilesGuardTest {

    @Test
    void failsWithoutRoleProfile() {
        MockEnvironment environment = new MockEnvironment();
        assertThatThrownBy(() -> new RoleProfilesGuard(environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api, worker or local");
    }

    @Test
    void acceptsEachRole() {
        for (String role : new String[] {"api", "worker", "local"}) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(role);
            assertThatCode(() -> new RoleProfilesGuard(environment).afterPropertiesSet())
                    .as("profile %s", role)
                    .doesNotThrowAnyException();
        }
    }
}
