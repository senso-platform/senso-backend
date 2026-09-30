package dev.senso.contracts.mqtt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Common envelope for every gateway message (ADR-0001 §6.2, schema {@code senso/mqtt/envelope.v1}).
 * Field names mirror the JSON Schema in {@code contracts/mqtt/envelope.schema.json}; correspondence is
 * enforced by {@code ContractExamplesTest} (INIT-PLAN phase 8), not by code generation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Envelope(
        @NotNull Integer schemaVersion,
        @NotNull UUID messageId,
        @NotBlank String gatewayId,
        @NotNull MessageType type,
        @NotNull Instant sentAt,
        @NotBlank String bootId,
        Long seq,
        Object payload,
        Signature signature) {}
