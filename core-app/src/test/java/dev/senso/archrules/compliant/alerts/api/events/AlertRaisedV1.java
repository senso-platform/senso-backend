package dev.senso.archrules.compliant.alerts.api.events;

import java.time.Instant;
import java.util.UUID;

/** Образец AGENTS.md §3.6: событие — record с суффиксом версии, поля только примитивы/UUID/Instant/String. */
public record AlertRaisedV1(UUID alertId, UUID deviceId, String severity, Instant occurredAt) {}
