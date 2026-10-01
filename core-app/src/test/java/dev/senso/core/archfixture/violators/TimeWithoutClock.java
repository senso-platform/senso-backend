package dev.senso.core.archfixture.violators;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * Нарушитель AGENTS.md §4: текущее время берут напрямую, а не из инжектированного Clock.
 * Фикстура именно в dev.senso.core.., потому что правило привязано к этому корню пакета.
 */
public class TimeWithoutClock {

    public Instant currentInstant() {
        return Instant.now();
    }

    public LocalDateTime currentLocal() {
        return LocalDateTime.now();
    }

    public OffsetDateTime currentOffset() {
        return OffsetDateTime.now();
    }

    public long currentMillis() {
        return System.currentTimeMillis();
    }

    public Clock ownClock() {
        return Clock.systemUTC();
    }
}
