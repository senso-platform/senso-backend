package dev.senso.ingest.validation;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import dev.senso.contracts.mqtt.MessageType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * JSON Schema контрактов MQTT ({@code contracts/mqtt}, копируются сборкой в {@code classpath:schemas/mqtt}).
 * Загружаются один раз при старте; без любой из схем приложение не стартует.
 *
 * <p>Проверка {@code format} ({@code date-time}, {@code uuid}) в draft 2020-12 по умолчанию только аннотация —
 * здесь она включена явно, иначе {@code "sentAt": "not-a-date"} проходил бы схему.
 */
@Component
public class MqttSchemas {

    private static final String ROOT = "schemas/mqtt/";

    private final Schema envelope;
    private final Map<MessageType, Schema> payloads = new EnumMap<>(MessageType.class);

    public MqttSchemas() {
        SchemaRegistryConfig config = SchemaRegistryConfig.builder()
                .formatAssertionsEnabled(true)
                .locale(Locale.ENGLISH) // текст ошибок уходит в x-reject-detail, не зависит от локали машины
                .build();
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaRegistryConfig(config));
        envelope = load(registry, ROOT + "envelope.schema.json");
        for (MessageType type : MessageType.values()) {
            payloads.put(type, load(registry, ROOT + "payloads/" + fileName(type) + ".schema.json"));
        }
    }

    public List<Error> validateEnvelope(JsonNode message) {
        return envelope.validate(message);
    }

    public List<Error> validatePayload(MessageType type, JsonNode payload) {
        return payloads.get(type).validate(payload);
    }

    /** {@code DEVICE_DISCOVERED} → {@code device-discovered}: так названы файлы в {@code contracts/mqtt/payloads}. */
    static String fileName(MessageType type) {
        return type.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static Schema load(SchemaRegistry registry, String resource) {
        try (InputStream in = MqttSchemas.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("JSON Schema not found on classpath: " + resource);
            }
            return registry.getSchema(in, InputFormat.JSON);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read JSON Schema " + resource, e);
        }
    }
}
