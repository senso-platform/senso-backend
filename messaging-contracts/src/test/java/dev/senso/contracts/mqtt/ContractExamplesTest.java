package dev.senso.contracts.mqtt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.senso.contracts.mqtt.payload.DeviceDiscoveredPayload;
import dev.senso.contracts.mqtt.payload.DeviceInventoryPayload;
import dev.senso.contracts.mqtt.payload.GatewayStatusPayload;
import dev.senso.contracts.mqtt.payload.SecurityEventPayload;
import dev.senso.contracts.mqtt.payload.TelemetryPayload;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records в messaging-contracts пишутся руками, а не генерируются из JSON Schema. Этот тест ловит дрейф:
 * поле переименовали/добавили в контракте, а record остался прежним (из-за {@code ignoreUnknown = true}
 * Jackson молча проглотил бы новое поле, и ingest/worker его бы потеряли).
 *
 * <p>Контракты читаются из сабмодуля {@code contracts/} (рабочая директория surefire — каталог модуля).
 */
class ContractExamplesTest {

    private static final Path MQTT = Path.of(System.getProperty("senso.contracts.dir", "../contracts"), "mqtt");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void recordComponentsMatchSchemaProperties() {
        assertSameFields(Envelope.class, properties("envelope.schema.json"));
        assertSameFields(TelemetryPayload.class, properties("payloads/telemetry.schema.json"));
        assertSameFields(TelemetryPayload.Sample.class, itemProperties("payloads/telemetry.schema.json", "samples"));
        assertSameFields(DeviceDiscoveredPayload.class, properties("payloads/device-discovered.schema.json"));
        assertSameFields(
                DeviceDiscoveredPayload.Capability.class,
                itemProperties("payloads/device-discovered.schema.json", "capabilities"));
        assertSameFields(DeviceInventoryPayload.class, properties("payloads/device-inventory.schema.json"));
        assertSameFields(
                DeviceInventoryPayload.Entry.class, itemProperties("payloads/device-inventory.schema.json", "devices"));
        assertSameFields(GatewayStatusPayload.class, properties("payloads/gateway-status.schema.json"));
        assertSameFields(SecurityEventPayload.class, properties("payloads/security-event.schema.json"));
        assertSameFields(
                SecurityEventPayload.Detector.class,
                objectProperties("payloads/security-event.schema.json", "detector"));
    }

    @Test
    void everyValidExampleDeserializesIntoRecords() throws IOException {
        List<Path> examples;
        try (Stream<Path> files = Files.list(MQTT.resolve("examples/valid"))) {
            examples =
                    files.filter(f -> f.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(examples).as("примеры из contracts/mqtt/examples/valid").isNotEmpty();

        for (Path example : examples) {
            Envelope envelope = JSON.readValue(example.toFile(), Envelope.class);
            assertThat(envelope.messageId()).as("%s: messageId", example).isNotNull();
            assertThat(envelope.type()).as("%s: type", example).isNotNull();
            assertThat(envelope.sentAt()).as("%s: sentAt", example).isNotNull();

            Object payload = JSON.convertValue(envelope.payload(), payloadClass(envelope.type()));
            assertThat(payload).as("%s: payload", example).isNotNull();
            if (payload instanceof TelemetryPayload telemetry) {
                assertThat(telemetry.samples()).as("%s: samples", example).isNotEmpty();
                telemetry.samples().forEach(sample -> assertThat(sample.ts()).isNotNull());
            }
        }
    }

    static Class<?> payloadClass(MessageType type) {
        return switch (type) {
            case TELEMETRY -> TelemetryPayload.class;
            case DEVICE_DISCOVERED -> DeviceDiscoveredPayload.class;
            case DEVICE_INVENTORY -> DeviceInventoryPayload.class;
            case SECURITY_EVENT -> SecurityEventPayload.class;
            case GATEWAY_STATUS -> GatewayStatusPayload.class;
        };
    }

    private static void assertSameFields(Class<?> recordType, Set<String> schemaProperties) {
        Set<String> components = Arrays.stream(recordType.getRecordComponents())
                .map(c -> c.getName())
                .collect(Collectors.toSet());
        assertThat(components)
                .as("поля %s должны совпадать с properties схемы", recordType.getSimpleName())
                .containsExactlyInAnyOrderElementsOf(schemaProperties);
    }

    private static Set<String> properties(String schema) {
        return propertiesOf(readSchema(schema));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> itemProperties(String schema, String arrayProperty) {
        Map<String, Object> array =
                (Map<String, Object>) propertyMap(readSchema(schema)).get(arrayProperty);
        return propertiesOf((Map<String, Object>) array.get("items"));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> objectProperties(String schema, String objectProperty) {
        return propertiesOf(
                (Map<String, Object>) propertyMap(readSchema(schema)).get(objectProperty));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertyMap(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    private static Set<String> propertiesOf(Map<String, Object> schema) {
        return propertyMap(schema).keySet();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readSchema(String schema) {
        return JSON.readValue(MQTT.resolve(schema).toFile(), Map.class);
    }
}
