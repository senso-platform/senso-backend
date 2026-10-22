package dev.senso.ingest.outbound;

import dev.senso.contracts.messaging.Exchanges;
import dev.senso.contracts.messaging.Headers;
import dev.senso.contracts.messaging.RoutingKeys;
import dev.senso.ingest.validation.MessageRejectionException;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Component;

/**
 * Кладёт отклонённое сообщение в {@code senso.dlq} с ключом {@code invalid} ({@code q.dlq.invalid}): тело —
 * исходные байты без изменений, причина — в заголовках.
 */
@Component
public class RejectPublisher {

    private final ConfirmedSender sender;

    public RejectPublisher(ConfirmedSender sender) {
        this.sender = sender;
    }

    /**
     * @param original сообщение из {@code q.ingest.raw}
     * @param gatewayId из routing key; {@code null}, если ключ не разобрался
     * @throws PublishFailedException брокер не подтвердил публикацию
     */
    public void publish(
            Message original, MessageRejectionException rejection, @Nullable String gatewayId, Instant receivedAt) {
        MessageProperties source = original.getMessageProperties();
        MessageProperties properties = new MessageProperties();
        properties.setHeader(Headers.X_REJECT_REASON, rejection.reason().name());
        properties.setHeader(Headers.X_REJECT_DETAIL, rejection.getMessage());
        if (gatewayId != null) {
            properties.setHeader(Headers.X_GATEWAY_ID, gatewayId);
        }
        if (rejection.messageId() != null) {
            properties.setHeader(Headers.X_MESSAGE_ID, rejection.messageId());
        }
        properties.setHeader(Headers.X_ORIGINAL_ROUTING_KEY, source.getReceivedRoutingKey());
        properties.setHeader(Headers.X_RECEIVED_AT, receivedAt.toString());
        Object traceparent = source.getHeader(Headers.TRACEPARENT);
        if (traceparent != null) {
            properties.setHeader(Headers.TRACEPARENT, traceparent.toString());
        }
        sender.send(Exchanges.DLQ, RoutingKeys.DLQ_INVALID, new Message(original.getBody(), properties));
    }
}
