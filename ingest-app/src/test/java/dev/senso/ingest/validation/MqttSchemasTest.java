package dev.senso.ingest.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import dev.senso.contracts.mqtt.MessageType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Схемы сами отклоняют {@code format}: без {@code formatAssertionsEnabled(true)} draft 2020-12 считает format
 * аннотацией, и {@code "sentAt": "not-a-date"} ловился бы только позже, при чтении в {@code Instant}.
 */
class MqttSchemasTest {

    private static final MqttSchemas SCHEMAS = new MqttSchemas();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void dateTimeFormatIsAsserted() {
        List<Error> errors = SCHEMAS.validateEnvelope(envelope().put("sentAt", "not-a-date"));

        assertThat(errors).singleElement().satisfies(e -> {
            assertThat(e.getKeyword()).isEqualTo("format");
            assertThat(e.getInstanceLocation().toString()).contains("sentAt");
        });
    }

    @Test
    void uuidFormatIsAsserted() {
        List<Error> errors = SCHEMAS.validateEnvelope(envelope().put("messageId", "not-a-uuid"));

        assertThat(errors)
                .singleElement()
                .satisfies(e -> assertThat(e.getKeyword()).isEqualTo("format"));
    }

    @Test
    void wellFormedEnvelopePasses() {
        assertThat(SCHEMAS.validateEnvelope(envelope())).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(MessageType.class)
    void everyMessageTypeHasPayloadSchema(MessageType type) {
        // пустой объект не проходит ни одну схему payload: у всех есть required
        assertThat(SCHEMAS.validatePayload(type, JSON.createObjectNode())).isNotEmpty();
    }

    private static ObjectNode envelope() {
        ObjectNode envelope = JSON.createObjectNode()
                .put("schemaVersion", 1)
                .put("messageId", "0195f3c2-7d1e-7a4b-9c11-2b6f0e5a8d34")
                .put("gatewayId", "gw-emulator-01")
                .put("type", "TELEMETRY")
                .put("sentAt", "2026-09-25T10:15:30.120Z")
                .put("bootId", "0195f3b8-0a2b-7c3d-9e4f-a1b2c3d4e5f6");
        envelope.putObject("payload");
        return envelope;
    }
}
