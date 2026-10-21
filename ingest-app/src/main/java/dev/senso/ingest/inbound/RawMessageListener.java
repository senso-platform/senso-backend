package dev.senso.ingest.inbound;

import dev.senso.contracts.internal.ValidatedMessageV1;
import dev.senso.contracts.messaging.Headers;
import dev.senso.contracts.messaging.Queues;
import dev.senso.contracts.mqtt.MessageType;
import dev.senso.ingest.outbound.RejectPublisher;
import dev.senso.ingest.outbound.ValidatedPublisher;
import dev.senso.ingest.validation.MessageRejectionException;
import dev.senso.ingest.validation.MessageValidator;
import dev.senso.ingest.validation.TopicAddress;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Приём сырого трафика шлюзов из {@code q.ingest.raw}. Тело — {@code byte[]} без content-type (так его отдаёт
 * MQTT-плагин), конвертер не используется. Подтверждение — auto: ack, когда метод вернулся.
 *
 * <ul>
 *   <li>валидно → {@code senso.validated} (с confirm) → ack;
 *   <li>нарушен контракт → исходные байты в {@code q.dlq.invalid} (с confirm) → ack;
 *   <li>любая другая ошибка (брокер, nack, таймаут confirm, unroutable) → исключение → 3 повтора в контейнере
 *       с паузой → reject без requeue → DLX → {@code q.dlq.failed} (настройка {@code retry} в application.yml).
 * </ul>
 */
@Component
public class RawMessageListener {

    static final String MESSAGES_METRIC = "ingest.messages";
    static final String VALIDATION_METRIC = "ingest.validation";
    static final String UNKNOWN_TYPE = "unknown";
    static final String NO_REASON = "none";

    private static final Logger log = LoggerFactory.getLogger(RawMessageListener.class);

    /** W3C trace context: {@code 00-<trace-id 32 hex>-<parent-id 16 hex>-<flags>}. */
    private static final Pattern TRACEPARENT = Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");

    private final MessageValidator validator;
    private final ValidatedPublisher validatedPublisher;
    private final RejectPublisher rejectPublisher;
    private final MeterRegistry meterRegistry;
    private final Timer validationTimer;
    private final Clock clock;

    public RawMessageListener(
            MessageValidator validator,
            ValidatedPublisher validatedPublisher,
            RejectPublisher rejectPublisher,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.validator = validator;
        this.validatedPublisher = validatedPublisher;
        this.rejectPublisher = rejectPublisher;
        this.meterRegistry = meterRegistry;
        this.validationTimer = Timer.builder(VALIDATION_METRIC)
                .description("Время проверки сообщения шлюза по контракту")
                .register(meterRegistry);
        this.clock = clock;
    }

    @RabbitListener(queues = Queues.INGEST_RAW)
    public void onMessage(Message message) {
        MessageProperties properties = message.getMessageProperties();
        String routingKey = properties.getReceivedRoutingKey();
        byte[] body = message.getBody();
        String traceparent = header(properties, Headers.TRACEPARENT);
        String type = UNKNOWN_TYPE;
        putMdc("traceId", traceId(traceparent));
        try {
            TopicAddress address = null;
            try {
                address = TopicAddress.parse(routingKey);
                putMdc("gatewayId", address.gatewayId());
                TopicAddress parsed = address;
                ValidatedMessageV1 validated = validationTimer.record(() -> validator.validate(parsed, body));
                type = validated.envelope().type().name();
                putMdc("messageId", validated.envelope().messageId().toString());

                validatedPublisher.publish(validated, traceparent);
                count(type, "accepted", NO_REASON);
                log.debug("Message accepted: type={} clockSkew={} bytes={}", type, validated.clockSkew(), body.length);
            } catch (MessageRejectionException rejection) {
                type = typeTag(rejection.type());
                putMdc("messageId", rejection.messageId());
                log.warn(
                        "Message rejected: reason={} detail={} gatewayId={} messageId={} routingKey={} bytes={}",
                        rejection.reason(),
                        rejection.getMessage(),
                        address == null ? null : address.gatewayId(),
                        rejection.messageId(),
                        routingKey,
                        body.length);
                rejectPublisher.publish(message, rejection, address == null ? null : address.gatewayId(), receivedAt());
                count(type, "rejected", rejection.reason().name());
            }
        } catch (RuntimeException e) {
            count(type, "error", NO_REASON);
            log.warn("Message will be redelivered: routingKey={} cause={}", routingKey, e.toString());
            throw e;
        } finally {
            MDC.remove("traceId");
            MDC.remove("gatewayId");
            MDC.remove("messageId");
        }
    }

    private Instant receivedAt() {
        return clock.instant();
    }

    private void count(String type, String result, String reason) {
        Counter.builder(MESSAGES_METRIC)
                .description("Сообщения шлюзов, обработанные ingest")
                .tag("type", type)
                .tag("result", result)
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }

    private static String typeTag(@Nullable MessageType type) {
        return type == null ? UNKNOWN_TYPE : type.name();
    }

    private static @Nullable String header(MessageProperties properties, String name) {
        Object value = properties.getHeader(name);
        return value == null ? null : value.toString();
    }

    static @Nullable String traceId(@Nullable String traceparent) {
        if (traceparent == null) {
            return null;
        }
        Matcher matcher = TRACEPARENT.matcher(traceparent);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static void putMdc(String key, @Nullable String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }
}
