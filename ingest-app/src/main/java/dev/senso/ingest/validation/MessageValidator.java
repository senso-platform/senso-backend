package dev.senso.ingest.validation;

import com.networknt.schema.Error;
import dev.senso.contracts.internal.ValidatedMessageV1;
import dev.senso.contracts.mqtt.Envelope;
import dev.senso.contracts.mqtt.MessageType;
import dev.senso.contracts.mqtt.MqttChannels;
import dev.senso.contracts.mqtt.payload.TelemetryPayload;
import dev.senso.ingest.config.IngestProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Проверка сырого сообщения шлюза по контракту (ADR-0001 §6, contracts/mqtt). Порядок проверок фиксирован: первая
 * сработавшая определяет {@link RejectReason}. Дубли здесь не ищутся — это задача приёмников в {@code core}.
 */
@Component
public class MessageValidator {

    private final MqttSchemas schemas;
    private final ObjectMapper objectMapper;
    private final SignatureVerifier signatureVerifier;
    private final IngestProperties properties;
    private final Clock clock;

    public MessageValidator(
            MqttSchemas schemas,
            ObjectMapper objectMapper,
            SignatureVerifier signatureVerifier,
            IngestProperties properties,
            Clock clock) {
        this.schemas = schemas;
        this.objectMapper = objectMapper;
        this.signatureVerifier = signatureVerifier;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @throws MessageRejectionException сообщение нарушает контракт
     */
    public ValidatedMessageV1 validate(TopicAddress address, byte[] body) {
        Instant now = clock.instant();

        if (body.length > properties.maxMessageBytes()) {
            throw reject(
                    RejectReason.PAYLOAD_TOO_LARGE,
                    "message is " + body.length + " bytes, limit " + properties.maxMessageBytes());
        }
        if (!address.knownChannel()) {
            throw reject(RejectReason.UNKNOWN_CHANNEL, "unknown inbound channel '" + address.channel() + "'");
        }

        JsonNode message = parse(body);
        try {
            return validateEnvelope(address, message, now);
        } catch (MessageRejectionException e) {
            throw e.withContext(messageId(message), messageType(message));
        }
    }

    private ValidatedMessageV1 validateEnvelope(TopicAddress address, JsonNode message, Instant now) {
        firstError(schemas.validateEnvelope(message), RejectReason.ENVELOPE_SCHEMA);
        Envelope envelope = convert(message, Envelope.class, RejectReason.ENVELOPE_SCHEMA);

        if (!address.gatewayId().equals(envelope.gatewayId())) {
            throw reject(
                    RejectReason.GATEWAY_MISMATCH,
                    "envelope gatewayId '" + envelope.gatewayId() + "' != topic gatewayId '" + address.gatewayId()
                            + "'");
        }
        if (!MqttChannels.accepts(address.channel(), envelope.type())) {
            throw reject(
                    RejectReason.CHANNEL_TYPE_MISMATCH,
                    "type " + envelope.type() + " is not allowed in channel '" + address.channel() + "'");
        }

        firstError(schemas.validatePayload(envelope.type(), message.get("payload")), RejectReason.PAYLOAD_SCHEMA);

        Instant latestAllowed = now.plus(properties.maxFutureSkew());
        checkNotInFuture("sentAt", envelope.sentAt(), latestAllowed);
        if (envelope.type() == MessageType.TELEMETRY) {
            TelemetryPayload telemetry =
                    convert(message.get("payload"), TelemetryPayload.class, RejectReason.PAYLOAD_SCHEMA);
            List<TelemetryPayload.Sample> samples = telemetry.samples();
            for (int i = 0; i < samples.size(); i++) {
                checkNotInFuture("payload.samples[" + i + "].ts", samples.get(i).ts(), latestAllowed);
            }
        }

        if (properties.signatureRequired() && !signatureVerifier.verify(envelope, message)) {
            throw reject(RejectReason.SIGNATURE_INVALID, "signature verification failed");
        }

        boolean clockSkew = Duration.between(envelope.sentAt(), now).abs().compareTo(properties.maxFutureSkew()) > 0;
        return new ValidatedMessageV1(envelope, now, address.gatewayId(), clockSkew);
    }

    private static @Nullable String text(JsonNode message, String field) {
        JsonNode value = message.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    /** Только корректный UUID: произвольная строка от шлюза не должна попасть в лог, MDC и заголовки DLQ. */
    private static @Nullable String messageId(JsonNode message) {
        String id = text(message, "messageId");
        if (id == null || id.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(id).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Для лога и тега метрики: только известные типы, иначе {@code null} (ограниченная кардинальность). */
    private static @Nullable MessageType messageType(JsonNode message) {
        String type = text(message, "type");
        return Arrays.stream(MessageType.values())
                .filter(t -> t.name().equals(type))
                .findFirst()
                .orElse(null);
    }

    private JsonNode parse(byte[] body) {
        JsonNode message;
        try {
            message = objectMapper.readTree(body);
        } catch (JacksonException e) {
            throw reject(RejectReason.MALFORMED_JSON, "not a valid JSON document: " + e.getOriginalMessage());
        }
        if (message == null || !message.isObject()) {
            throw reject(RejectReason.MALFORMED_JSON, "JSON document is not an object");
        }
        return message;
    }

    /** Схема уже проверила форму; ошибка здесь — значение, которое схема пропустила, а Java-тип не принял. */
    private <T> T convert(JsonNode node, Class<T> type, RejectReason reason) {
        try {
            return objectMapper.treeToValue(node, type);
        } catch (JacksonException e) {
            throw reject(reason, "cannot read " + type.getSimpleName() + ": " + e.getOriginalMessage());
        }
    }

    private static void firstError(List<Error> errors, RejectReason reason) {
        if (!errors.isEmpty()) {
            Error first = errors.getFirst();
            throw reject(reason, first.getInstanceLocation() + ": " + first.getMessage());
        }
    }

    private static void checkNotInFuture(String field, Instant value, Instant latestAllowed) {
        if (value.isAfter(latestAllowed)) {
            throw reject(
                    RejectReason.TIMESTAMP_IN_FUTURE,
                    field + " " + value + " is later than receive time + allowed skew (" + latestAllowed + ")");
        }
    }

    private static MessageRejectionException reject(RejectReason reason, String detail) {
        return new MessageRejectionException(reason, detail);
    }
}
