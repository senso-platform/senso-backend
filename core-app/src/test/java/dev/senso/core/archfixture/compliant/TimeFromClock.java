package dev.senso.core.archfixture.compliant;

import java.time.Clock;
import java.time.Instant;

/** Образец AGENTS.md §4: время из инжектированного Clock. */
public class TimeFromClock {

    private final Clock clock;

    public TimeFromClock(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }
}
