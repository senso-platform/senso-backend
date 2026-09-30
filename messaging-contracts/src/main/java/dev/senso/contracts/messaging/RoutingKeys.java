package dev.senso.contracts.messaging;

/** Routing keys. Never inline these strings in code (AGENTS.md §6). */
public final class RoutingKeys {

    /** Binding for raw gateway traffic arriving via the MQTT plugin on {@code amq.topic}. */
    public static final String MQTT_ALL = "senso.v1.gw.*.*";

    public static final String TELEMETRY_V1 = "telemetry.v1";
    public static final String DEVICE_DISCOVERED_V1 = "device.discovered.v1";
    public static final String DEVICE_INVENTORY_V1 = "device.inventory.v1";
    public static final String SECURITY_EVENT_V1 = "security.event.v1";
    public static final String GATEWAY_STATUS_V1 = "gateway.status.v1";

    public static final String DLQ_INVALID = "invalid";

    private RoutingKeys() {}
}
