package dev.senso.contracts.mqtt.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Payload of {@code TELEMETRY} (ADR-0001 §6.3). Max 500 samples per message. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelemetryPayload(List<Sample> samples) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sample(@NotBlank String deviceId, Instant ts, Map<String, Double> metrics) {}
}
