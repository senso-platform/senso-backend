package dev.senso.kernel.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ClockConfigTest {

    @Test
    void clockIsUtcWithMicrosecondPrecisionLikeTimestamptz() {
        Clock clock = new ClockConfig().clock();
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        for (int i = 0; i < 100; i++) {
            Instant now = clock.instant();
            assertThat(now.getNano() % 1_000)
                    .as("наносекунды за пределами микросекунд")
                    .isZero();
        }
    }
}
