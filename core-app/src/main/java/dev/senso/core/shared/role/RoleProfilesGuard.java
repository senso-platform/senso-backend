package dev.senso.core.shared.role;

import java.util.Arrays;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Не даёт стартовать без роли. Без профиля {@code api}, {@code worker} или {@code local} приложение поднималось бы
 * молча и без единого контроллера и листенера (все они под {@link ApiRole}/{@link WorkerRole}).
 * В тестах роль задаётся через {@code @ActiveProfiles}.
 */
@Component
public class RoleProfilesGuard implements InitializingBean {

    static final Profiles ANY_ROLE = Profiles.of("api | worker | local");

    private final Environment environment;

    public RoleProfilesGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (!environment.acceptsProfiles(ANY_ROLE)) {
            throw new IllegalStateException("core-app needs a role profile: api, worker or local"
                    + " (SPRING_PROFILES_ACTIVE / -Dspring-boot.run.profiles / @ActiveProfiles in tests)."
                    + " Active profiles: " + Arrays.toString(environment.getActiveProfiles()));
        }
    }
}
