package dev.senso.core.telemetry.internal.persistence;

import java.time.Instant;
import java.util.UUID;

/** Одна строка {@code telemetry.measurements}: значение метрики устройства в момент {@code ts}. */
public record Measurement(Instant ts, UUID deviceId, String metric, double value) {}
