package dev.senso.contracts.mqtt;

import java.util.Map;
import java.util.Set;

/**
 * MQTT-каналы шлюза и типы, которым они принадлежат (contracts/mqtt/TOPICS.md).
 * ingest сверяет канал из routing key с полем {@code type} envelope — несовпадение отклоняется.
 */
public final class MqttChannels {

    public static final String TELEMETRY = "telemetry";
    public static final String EVENTS = "events";
    public static final String STATUS = "status";
    public static final String CMD = "cmd";
    public static final String ACK = "ack";

    private static final Map<String, Set<MessageType>> TYPES_BY_CHANNEL = Map.of(
            TELEMETRY,
            Set.of(MessageType.TELEMETRY),
            EVENTS,
            Set.of(MessageType.DEVICE_DISCOVERED, MessageType.DEVICE_INVENTORY, MessageType.SECURITY_EVENT),
            STATUS,
            Set.of(MessageType.GATEWAY_STATUS));

    private MqttChannels() {}

    public static boolean isKnown(String channel) {
        return TYPES_BY_CHANNEL.containsKey(channel);
    }

    public static boolean accepts(String channel, MessageType type) {
        Set<MessageType> allowed = TYPES_BY_CHANNEL.get(channel);
        return allowed != null && allowed.contains(type);
    }
}
