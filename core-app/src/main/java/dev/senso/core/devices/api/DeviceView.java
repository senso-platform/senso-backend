package dev.senso.core.devices.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DeviceView(
        UUID id,
        UUID gatewayId,
        String externalId,
        String name,
        String deviceType,
        DeviceStatus status,
        List<DeviceCapability> capabilities,
        Map<String, Object> metadata,
        Connectivity connectivity,
        Instant lastSeenAt,
        UUID approvedBy,
        Instant approvedAt,
        Instant createdAt) {

    public DeviceView {
        capabilities = List.copyOf(capabilities);
        metadata = immutableObject(metadata);
    }

    private static Map<String, Object> immutableObject(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put((String) key, immutableValue(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
