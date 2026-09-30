package dev.senso.contracts.mqtt.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

/** Payload of {@code DEVICE_DISCOVERED} (ADR-0001 §6.3). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeviceDiscoveredPayload(
        @NotBlank String deviceId,
        @NotBlank String deviceType,
        List<Capability> capabilities,
        Map<String, Object> metadata) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Capability(String metric, String unit) {}
}
