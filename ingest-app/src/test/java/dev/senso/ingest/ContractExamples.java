package dev.senso.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.senso.contracts.messaging.RoutingKeys;
import dev.senso.contracts.mqtt.MessageType;
import dev.senso.contracts.mqtt.MqttChannels;
import dev.senso.ingest.validation.RejectReason;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Примеры сообщений из {@code contracts/mqtt/examples} (копируются сборкой в test-classpath, см. ingest-app/pom.xml)
 * и что ingest с ними должен сделать.
 */
public final class ContractExamples {

    /** Ожидаемая причина для каждого файла из examples/invalid; новый файл без строки здесь роняет тесты. */
    public static final Map<String, RejectReason> INVALID_REASONS = Map.of(
            "future-timestamp.json", RejectReason.TIMESTAMP_IN_FUTURE,
            "missing-message-id.json", RejectReason.ENVELOPE_SCHEMA,
            "too-many-samples.json", RejectReason.PAYLOAD_SCHEMA,
            // DEVICE_PING нет в enum type конверта — отсекается схемой раньше сверки канала и типа
            "wrong-type.json", RejectReason.ENVELOPE_SCHEMA);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ContractExamples() {}

    public static List<Path> valid() {
        return list("valid");
    }

    public static List<Path> invalid() {
        return list("invalid");
    }

    public static RejectReason expectedReason(Path invalidExample) {
        String file = invalidExample.getFileName().toString();
        assertThat(INVALID_REASONS).as("ожидаемая причина для %s", file).containsKey(file);
        return INVALID_REASONS.get(file);
    }

    public static byte[] bytes(Path example) {
        try {
            return Files.readAllBytes(example);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode json(Path example) {
        return JSON.readTree(bytes(example));
    }

    public static MessageType type(Path validExample) {
        return MessageType.valueOf(json(validExample).get("type").asString());
    }

    public static String gatewayId(Path example) {
        return json(example).get("gatewayId").asString();
    }

    /** MQTT-канал, в который шлюз публикует сообщения этого типа (contracts/mqtt/TOPICS.md). */
    public static String channel(MessageType type) {
        return switch (type) {
            case TELEMETRY -> MqttChannels.TELEMETRY;
            case DEVICE_DISCOVERED, DEVICE_INVENTORY, SECURITY_EVENT -> MqttChannels.EVENTS;
            case GATEWAY_STATUS -> MqttChannels.STATUS;
        };
    }

    /** Routing key, с которым MQTT-плагин кладёт сообщение в {@code amq.topic}. */
    public static String rawRoutingKey(String gatewayId, String channel) {
        return RoutingKeys.MQTT_PREFIX + "." + gatewayId + "." + channel;
    }

    public static Path dir(String kind) {
        try {
            return Path.of(ContractExamples.class
                    .getClassLoader()
                    .getResource("contracts/mqtt/examples/" + kind)
                    .toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Path> list(String kind) {
        Path dir = dir(kind);
        try (var files = Files.list(dir)) {
            List<Path> json =
                    files.filter(f -> f.toString().endsWith(".json")).sorted().toList();
            assertThat(json).as("примеры в %s", dir).isNotEmpty();
            return json;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
