package dev.senso.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.senso.contracts.messaging.Exchanges;
import dev.senso.contracts.messaging.Headers;
import dev.senso.contracts.messaging.Queues;
import dev.senso.contracts.messaging.RoutingKeys;
import dev.senso.contracts.mqtt.MessageType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Base64UrlNamingStrategy;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Binding.DestinationType;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

/**
 * Поток ingest на реальной топологии (S2, задача 6): брокер с deploy/rabbitmq (vhost {@code senso}, пользователь
 * {@code senso-ingest}), сообщения публикуются по AMQP в {@code amq.topic} с ключом
 * {@code senso.v1.gw.<gatewayId>.<channel>} — так их кладёт MQTT-плагин.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class IngestFlowIT extends IngestIntegrationTestBase {

    private static final long RECEIVE_TIMEOUT_MS = Duration.ofSeconds(10).toMillis();
    /** Ждём «лишнее» сообщение недолго: всё нужное к этому моменту уже пришло. */
    private static final long NOTHING_MORE_MS = 500;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    AmqpAdmin amqpAdmin;

    @Autowired
    MeterRegistry meterRegistry;

    /** Временная очередь на {@code senso.validated} / {@code #} — вместо потребителей core. */
    private Queue validated;

    @BeforeEach
    void cleanQueues() {
        amqpAdmin.purgeQueue(Queues.INGEST_RAW, false);
        amqpAdmin.purgeQueue(Queues.DLQ_INVALID, false);
        amqpAdmin.purgeQueue(Queues.DLQ_FAILED, false);
    }

    @AfterEach
    void unbindValidated() {
        if (validated != null) {
            amqpAdmin.deleteQueue(validated.getName());
            validated = null;
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.senso.ingest.ContractExamples#valid")
    void validExampleIsPublishedToValidatedExchange(Path example) {
        bindValidatedQueue();
        MessageType type = ContractExamples.type(example);
        JsonNode json = ContractExamples.json(example);

        publishRaw(
                ContractExamples.gatewayId(example), ContractExamples.channel(type), ContractExamples.bytes(example));

        Message published = rabbitTemplate.receive(validated.getName(), RECEIVE_TIMEOUT_MS);
        assertThat(published).as("сообщение в senso.validated").isNotNull();
        MessageProperties properties = published.getMessageProperties();
        assertThat(properties.getReceivedRoutingKey()).isEqualTo(RoutingKeys.forType(type));
        assertThat((String) properties.getHeader(Headers.X_MESSAGE_ID))
                .isEqualTo(json.get("messageId").asString());
        assertThat((String) properties.getHeader(Headers.X_GATEWAY_ID)).isEqualTo(ContractExamples.gatewayId(example));
        assertThat(rabbitTemplate.receive(validated.getName(), NOTHING_MORE_MS))
                .as("ровно одно сообщение")
                .isNull();
        assertThat(rabbitTemplate.receive(Queues.DLQ_INVALID, NOTHING_MORE_MS)).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.senso.ingest.ContractExamples#invalid")
    void invalidExampleGoesToDlqWithReasonAndOriginalBytes(Path example) {
        bindValidatedQueue();
        byte[] body = ContractExamples.bytes(example);

        // тип в невалидном примере может быть любым — все они про телеметрию, канал telemetry
        publishRaw(ContractExamples.gatewayId(example), "telemetry", body);

        Message rejected = rabbitTemplate.receive(Queues.DLQ_INVALID, RECEIVE_TIMEOUT_MS);
        assertThat(rejected).as("сообщение в q.dlq.invalid").isNotNull();
        assertThat(rejected.getBody()).as("тело байт-в-байт").isEqualTo(body);
        Map<String, Object> headers = rejected.getMessageProperties().getHeaders();
        assertThat(headers)
                .containsEntry(
                        Headers.X_REJECT_REASON,
                        ContractExamples.expectedReason(example).name())
                .containsEntry(Headers.X_GATEWAY_ID, ContractExamples.gatewayId(example))
                .containsEntry(
                        Headers.X_ORIGINAL_ROUTING_KEY,
                        ContractExamples.rawRoutingKey(ContractExamples.gatewayId(example), "telemetry"))
                .containsKeys(Headers.X_REJECT_DETAIL, Headers.X_RECEIVED_AT);
        assertThat(rabbitTemplate.receive(Queues.DLQ_INVALID, NOTHING_MORE_MS))
                .as("ровно одно сообщение")
                .isNull();
        assertThat(rabbitTemplate.receive(validated.getName(), NOTHING_MORE_MS)).isNull();
    }

    /**
     * Фактическое поведение (решение TL 2026-10-19, HANDOFF-20261019-s2 §3): на {@code senso.validated} нет очереди
     * для ключа → publisher confirm с returned → 1 попытка + 3 повтора в листенере → reject без requeue → DLX
     * {@code senso.dlq} / {@code failed} → {@code q.dlq.failed}. Сообщение не теряется и не крутится бесконечно.
     */
    @Test
    void unroutableValidMessageEndsUpInFailedDlqAfterRetries() {
        Path example = ContractExamples.valid().stream()
                .filter(p -> ContractExamples.type(p) == MessageType.TELEMETRY)
                .findFirst()
                .orElseThrow();
        byte[] body = ContractExamples.bytes(example);
        double errorsBefore = errors(MessageType.TELEMETRY);

        publishRaw(ContractExamples.gatewayId(example), "telemetry", body);

        Message failed = rabbitTemplate.receive(Queues.DLQ_FAILED, RECEIVE_TIMEOUT_MS);
        assertThat(failed).as("сообщение в q.dlq.failed").isNotNull();
        assertThat(failed.getBody()).isEqualTo(body);
        assertThat(failed.getMessageProperties().getHeaders())
                .containsEntry("x-first-death-queue", Queues.INGEST_RAW)
                .containsEntry("x-first-death-reason", "rejected");
        assertThat(errors(MessageType.TELEMETRY) - errorsBefore)
                .as("1 попытка + 3 повтора")
                .isEqualTo(4);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(
                                amqpAdmin.getQueueInfo(Queues.INGEST_RAW).getMessageCount())
                        .isZero());
        assertThat(rabbitTemplate.receive(Queues.DLQ_INVALID, NOTHING_MORE_MS)).isNull();
    }

    private void bindValidatedQueue() {
        // exclusive (transient non-exclusive в RabbitMQ 4.x запрещены), но не auto-delete: receive() с таймаутом
        // подписывается и отписывается, auto-delete-очередь исчезла бы после первого сообщения
        validated = new Queue(Base64UrlNamingStrategy.DEFAULT.generateName(), false, true, false);
        amqpAdmin.declareQueue(validated);
        amqpAdmin.declareBinding(
                new Binding(validated.getName(), DestinationType.QUEUE, Exchanges.VALIDATED, "#", null));
    }

    private void publishRaw(String gatewayId, String channel, byte[] body) {
        rabbitTemplate.send(
                Exchanges.AMQ_TOPIC,
                ContractExamples.rawRoutingKey(gatewayId, channel),
                new Message(body, new MessageProperties()));
    }

    private double errors(MessageType type) {
        Counter counter = meterRegistry
                .find("ingest.messages")
                .tag("type", type.name())
                .tag("result", "error")
                .counter();
        return counter == null ? 0 : counter.count();
    }
}
