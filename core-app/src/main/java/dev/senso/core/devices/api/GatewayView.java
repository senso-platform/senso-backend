package dev.senso.core.devices.api;

import java.time.Instant;
import java.util.UUID;

/** Registry data only: MQTT credentials and their hashes are never exposed here. */
public record GatewayView(
        UUID id,
        UUID ownerId,
        String externalId,
        String name,
        GatewayState state,
        Instant lastSeenAt,
        String firmware,
        Instant createdAt) {}
