package dev.senso.contracts.mqtt.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

/** Payload of {@code GATEWAY_STATUS}, including the Last Will message (ADR-0001 §6.3). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GatewayStatusPayload(
        @NotBlank String state, String firmware, Long uptimeSec, Integer deviceCount, Integer queueDepth) {}
