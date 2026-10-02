package dev.senso.kernel.time;

import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Единственный источник текущего времени (AGENTS.md §4). Автоконфигурация: подключается в любом приложении,
 * где {@code platform-kernel} в classpath, {@code @Import} не нужен.
 *
 * <p>Тик 1 мкс: столько же хранит PostgreSQL {@code timestamptz}. Иначе {@code Instant} в памяти (наносекунды на
 * Linux) не равен тому же значению, прочитанному из БД, и ломаются сравнения в тестах и keyset-курсоры.
 *
 * <p>В тесте подменить: объявить свой бин {@code Clock} (например, {@code Clock.fixed(...)}) — этот отступит.
 */
@AutoConfiguration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
