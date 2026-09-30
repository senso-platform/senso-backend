package dev.senso.contracts.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.senso.contracts.mqtt.Envelope;
import java.time.Instant;

/**
 * What ingest publishes to {@code senso.validated} after schema and limit checks (INIT-PLAN phase 6).
 * {@code gatewayId} is the value taken from the routing key, re-verified against the envelope.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ValidatedMessageV1(Envelope envelope, Instant receivedAt, String gatewayId, boolean clockSkew) {}
