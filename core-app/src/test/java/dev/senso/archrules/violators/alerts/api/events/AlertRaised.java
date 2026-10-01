package dev.senso.archrules.violators.alerts.api.events;

import java.util.UUID;

/** Нарушитель AGENTS.md §3.6: межмодульное событие — обычный класс и без суффикса версии. */
public class AlertRaised {

    private UUID deviceId;

    public UUID deviceId() {
        return deviceId;
    }
}
