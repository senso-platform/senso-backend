package dev.senso.archrules.violators.devices.internal.config;

import org.springframework.beans.factory.annotation.Value;

/** Нарушитель AGENTS.md §4: настройка через @Value вместо @ConfigurationProperties record. */
public class ValueInjection {

    @Value("${senso.devices.pending-limit:50}")
    private int pendingLimit;

    public int pendingLimit() {
        return pendingLimit;
    }
}
