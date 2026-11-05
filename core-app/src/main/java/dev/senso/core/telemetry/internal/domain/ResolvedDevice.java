package dev.senso.core.telemetry.internal.domain;

import java.util.UUID;

/** Устройство платформы; телеметрия пишется только для {@code active}. */
public record ResolvedDevice(UUID deviceId, boolean active) {}
