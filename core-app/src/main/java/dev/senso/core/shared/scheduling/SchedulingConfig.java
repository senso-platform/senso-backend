package dev.senso.core.shared.scheduling;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Планировщики и распределённые блокировки (ADR-0001 §7.1, §12.4).
 *
 * <p>Включено для обеих ролей: worker держит фоновые задачи, api — heartbeat SSE. Какая задача в какой роли,
 * решает аннотация роли на классе с {@code @Scheduled} (это проверяет {@code ArchitectureTest}).
 *
 * <p>Задача, которая не должна выполняться параллельно на нескольких инстансах, помечается
 * {@code @SchedulerLock(name = "<module>.<task>")}. Таблица блокировок — {@code platform.shedlock}
 * (миграция {@code platform_init}); без явного имени со схемой ShedLock искал бы {@code shedlock} в {@code public}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulingConfig {

    static final String LOCK_TABLE = "platform.shedlock";

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName(LOCK_TABLE)
                .usingDbTime()
                .build());
    }
}
