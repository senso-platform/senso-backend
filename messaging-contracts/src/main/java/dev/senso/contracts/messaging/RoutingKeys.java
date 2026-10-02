package dev.senso.contracts.messaging;

import dev.senso.contracts.mqtt.MessageType;

/** Routing keys. Never inline these strings in code (AGENTS.md §6). */
public final class RoutingKeys {

    /** Префикс ключа сырого трафика шлюзов: {@code senso.v1.gw.<gatewayId>.<channel>}. */
    public static final String MQTT_PREFIX = "senso.v1.gw";

    /** Binding for raw gateway traffic arriving via the MQTT plugin on {@code amq.topic}. */
    public static final String MQTT_ALL = "senso.v1.gw.*.*";

    public static final String TELEMETRY_V1 = "telemetry.v1";
    public static final String DEVICE_DISCOVERED_V1 = "device.discovered.v1";
    public static final String DEVICE_INVENTORY_V1 = "device.inventory.v1";
    public static final String SECURITY_EVENT_V1 = "security.event.v1";
    public static final String GATEWAY_STATUS_V1 = "gateway.status.v1";

    public static final String DLQ_INVALID = "invalid";
    public static final String DLQ_FAILED = "failed";

    /** Ключ для публикации валидного сообщения в {@code senso.validated}. */
    public static String forType(MessageType type) {
        return switch (type) {
            case TELEMETRY -> TELEMETRY_V1;
            case DEVICE_DISCOVERED -> DEVICE_DISCOVERED_V1;
            case DEVICE_INVENTORY -> DEVICE_INVENTORY_V1;
            case SECURITY_EVENT -> SECURITY_EVENT_V1;
            case GATEWAY_STATUS -> GATEWAY_STATUS_V1;
        };
    }

    private RoutingKeys() {}
}
