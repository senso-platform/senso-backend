package dev.senso.ingest.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.senso.contracts.internal.ValidatedMessageV1;
import dev.senso.contracts.messaging.Exchanges;
import dev.senso.contracts.messaging.Headers;
import dev.senso.contracts.messaging.RoutingKeys;
import dev.senso.contracts.mqtt.Envelope;
import dev.senso.contracts.mqtt.MessageType;
import dev.senso.ingest.validation.MessageRejectionException;
import dev.senso.ingest.validation.RejectReason;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

/** Что и с какими заголовками уходит в брокер; сама доставка с confirm — {@link ConfirmedSenderTest}. */
class PublishersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TRACEPARENT = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
    private static final Instant RECEIVED_AT = Instant.parse("2026-09-25T10:16:00Z");

    private final ConfirmedSender sender = mock(ConfirmedSender.class);

    @Test
    void validatedMessageGoesToValidatedExchangeWithTypeKeyAndHeaders() {
        UUID messageId = UUID.fromString("0195f3c2-7d1e-7a4b-9c11-2b6f0e5a8d34");
        ValidatedMessageV1 validated = new ValidatedMessageV1(
                new Envelope(
                        1,
                        messageId,
                        "gw-emulator-01",
                        MessageType.TELEMETRY,
                        Instant.parse("2026-09-25T10:15:30.120Z"),
                        "0195f3b8-0a2b-7c3d-9e4f-a1b2c3d4e5f6",
                        10L,
                        Map.of("samples", List.of()),
                        null),
                RECEIVED_AT,
                "gw-emulator-01",
                false);

        new ValidatedPublisher(sender, JSON).publish(validated, TRACEPARENT);

        Message sent = captureSent(Exchanges.VALIDATED, RoutingKeys.TELEMETRY_V1);
        MessageProperties properties = sent.getMessageProperties();
        assertThat((String) properties.getHeader(Headers.X_MESSAGE_ID)).isEqualTo(messageId.toString());
        assertThat(properties.getMessageId()).isEqualTo(messageId.toString());
        assertThat((String) properties.getHeader(Headers.X_GATEWAY_ID)).isEqualTo("gw-emulator-01");
        assertThat((String) properties.getHeader(Headers.X_RECEIVED_AT)).isEqualTo(RECEIVED_AT.toString());
        assertThat((String) properties.getHeader(Headers.TRACEPARENT)).isEqualTo(TRACEPARENT);
        assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(JSON.readValue(sent.getBody(), ValidatedMessageV1.class)).isEqualTo(validated);
    }

    @Test
    void validatedMessageWithoutIncomingTraceHasNoTraceparent() {
        ValidatedMessageV1 validated = new ValidatedMessageV1(
                new Envelope(
                        1,
                        UUID.randomUUID(),
                        "gw-1",
                        MessageType.GATEWAY_STATUS,
                        RECEIVED_AT,
                        "boot-0001",
                        null,
                        Map.of("state", "ONLINE"),
                        null),
                RECEIVED_AT,
                "gw-1",
                false);

        new ValidatedPublisher(sender, JSON).publish(validated, null);

        Message sent = captureSent(Exchanges.VALIDATED, RoutingKeys.GATEWAY_STATUS_V1);
        assertThat(sent.getMessageProperties().getHeaders()).doesNotContainKey(Headers.TRACEPARENT);
    }

    @Test
    void rejectedMessageKeepsOriginalBytesAndCarriesReasonHeaders() {
        byte[] body = "{\"broken\": tru".getBytes(StandardCharsets.UTF_8);
        MessageProperties incoming = new MessageProperties();
        incoming.setReceivedRoutingKey("senso.v1.gw.gw-emulator-01.telemetry");
        incoming.setHeader(Headers.TRACEPARENT, TRACEPARENT);
        MessageRejectionException rejection =
                new MessageRejectionException(RejectReason.MALFORMED_JSON, "not a valid JSON document");

        new RejectPublisher(sender).publish(new Message(body, incoming), rejection, "gw-emulator-01", RECEIVED_AT);

        Message sent = captureSent(Exchanges.DLQ, RoutingKeys.DLQ_INVALID);
        assertThat(sent.getBody()).isEqualTo(body);
        Map<String, Object> headers = sent.getMessageProperties().getHeaders();
        assertThat(headers)
                .containsEntry(Headers.X_REJECT_REASON, "MALFORMED_JSON")
                .containsEntry(Headers.X_REJECT_DETAIL, "not a valid JSON document")
                .containsEntry(Headers.X_GATEWAY_ID, "gw-emulator-01")
                .containsEntry(Headers.X_ORIGINAL_ROUTING_KEY, "senso.v1.gw.gw-emulator-01.telemetry")
                .containsEntry(Headers.X_RECEIVED_AT, RECEIVED_AT.toString())
                .containsEntry(Headers.TRACEPARENT, TRACEPARENT)
                .doesNotContainKey(Headers.X_MESSAGE_ID);
    }

    @Test
    void rejectedMessageWithUnparsableKeyHasNoGatewayHeader() {
        MessageProperties incoming = new MessageProperties();
        incoming.setReceivedRoutingKey("senso.v1.gw..telemetry");
        MessageRejectionException rejection = new MessageRejectionException(RejectReason.ROUTING_KEY, "bad key");

        new RejectPublisher(sender).publish(new Message(new byte[] {1}, incoming), rejection, null, RECEIVED_AT);

        Message sent = captureSent(Exchanges.DLQ, RoutingKeys.DLQ_INVALID);
        assertThat(sent.getMessageProperties().getHeaders())
                .containsEntry(Headers.X_REJECT_REASON, "ROUTING_KEY")
                .doesNotContainKey(Headers.X_GATEWAY_ID);
    }

    private Message captureSent(String exchange, String routingKey) {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sender).send(eq(exchange), eq(routingKey), captor.capture());
        return captor.getValue();
    }
}
