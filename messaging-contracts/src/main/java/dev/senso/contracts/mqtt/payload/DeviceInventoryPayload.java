package dev.senso.contracts.mqtt.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Payload of {@code DEVICE_INVENTORY}: full list of sensors attached to the gateway.
 * Devices missing from the list are considered disconnected (INIT-PLAN phase 0).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeviceInventoryPayload(List<Entry> devices) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String deviceId, String deviceType, List<DeviceDiscoveredPayload.Capability> capabilities) {}
}
