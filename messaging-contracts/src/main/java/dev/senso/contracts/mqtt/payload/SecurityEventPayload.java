package dev.senso.contracts.mqtt.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Map;

/** Payload of {@code SECURITY_EVENT} (ADR-0001 §6.3). {@code deviceId} may be null for gateway-level events. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SecurityEventPayload(
        String deviceId,
        @NotBlank String severity,
        @NotBlank String code,
        @NotBlank String message,
        Instant detectedAt,
        Detector detector,
        Map<String, Object> details) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Detector(String name, String version) {}
}
