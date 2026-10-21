package dev.senso.ingest.outbound;

import dev.senso.contracts.internal.ValidatedMessageV1;
import dev.senso.contracts.messaging.Exchanges;
import dev.senso.contracts.messaging.Headers;
import dev.senso.contracts.messaging.RoutingKeys;
import org.jspecify.annotations.Nullable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Публикует проверенное сообщение в {@code senso.validated} с ключом по типу ({@code telemetry.v1} и т. д.).
 * JSON-конвертер Jackson 3 используется только здесь: общий {@code RabbitTemplate} остаётся без конвертера, им же
 * в DLQ уходят исходные байты.
 */
@Component
public class ValidatedPublisher {

    private final ConfirmedSender sender;
    private final JacksonJsonMessageConverter converter;

    public ValidatedPublisher(ConfirmedSender sender, JsonMapper jsonMapper) {
        this.sender = sender;
        this.converter = new JacksonJsonMessageConverter(jsonMapper);
    }

    /**
     * @param traceparent W3C trace context входящего сообщения, если был
     * @throws PublishFailedException брокер не подтвердил или сообщение некуда доставить
     */
    public void publish(ValidatedMessageV1 validated, @Nullable String traceparent) {
        MessageProperties properties = new MessageProperties();
        String messageId = validated.envelope().messageId().toString();
        properties.setMessageId(messageId);
        properties.setHeader(Headers.X_MESSAGE_ID, messageId);
        properties.setHeader(Headers.X_GATEWAY_ID, validated.gatewayId());
        properties.setHeader(Headers.X_RECEIVED_AT, validated.receivedAt().toString());
        if (traceparent != null) {
            properties.setHeader(Headers.TRACEPARENT, traceparent);
        }
        Message message = converter.toMessage(validated, properties);
        sender.send(
                Exchanges.VALIDATED, RoutingKeys.forType(validated.envelope().type()), message);
    }
}
