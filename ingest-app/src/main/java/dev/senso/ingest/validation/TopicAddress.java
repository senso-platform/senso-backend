package dev.senso.ingest.validation;

import dev.senso.contracts.messaging.RoutingKeys;
import dev.senso.contracts.mqtt.MqttChannels;

/**
 * Адрес сырого сообщения из routing key {@code senso.v1.gw.<gatewayId>.<channel>} (MQTT-плагин заменяет
 * {@code /} топика на {@code .}). Разбирается только форма ключа; известен ли канал — {@link #knownChannel()},
 * решение принимает валидатор.
 */
public record TopicAddress(String gatewayId, String channel) {

    private static final String PREFIX = RoutingKeys.MQTT_PREFIX + ".";

    public static TopicAddress parse(String routingKey) {
        if (routingKey == null || !routingKey.startsWith(PREFIX)) {
            throw invalid(routingKey);
        }
        String[] parts = routingKey.substring(PREFIX.length()).split("\\.", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw invalid(routingKey);
        }
        return new TopicAddress(parts[0], parts[1]);
    }

    /** Входящий канал шлюза: {@code telemetry}, {@code events} или {@code status}. */
    public boolean knownChannel() {
        return MqttChannels.isKnown(channel);
    }

    private static MessageRejectionException invalid(String routingKey) {
        return new MessageRejectionException(
                RejectReason.ROUTING_KEY, "routing key does not match " + RoutingKeys.MQTT_ALL + ": " + routingKey);
    }
}
