package dev.senso.ingest.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.senso.contracts.internal.ValidatedMessageV1;
import dev.senso.contracts.messaging.Headers;
import dev.senso.contracts.mqtt.Envelope;
import dev.senso.contracts.mqtt.MessageType;
import dev.senso.ingest.outbound.PublishFailedException;
import dev.senso.ingest.outbound.RejectPublisher;
import dev.senso.ingest.outbound.ValidatedPublisher;
import dev.senso.ingest.validation.MessageRejectionException;
import dev.senso.ingest.validation.MessageValidator;
import dev.senso.ingest.validation.RejectReason;
import dev.senso.ingest.validation.TopicAddress;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/** Ветвление листенера при замоканных валидаторе и паблишерах (S2, задача 5). */
class RawMessageListenerTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:16:00Z");
    private static final String ROUTING_KEY = "senso.v1.gw.gw-emulator-01.telemetry";
    private static final String TRACEPARENT = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);

    private final MessageValidator validator = mock(MessageValidator.class);
    private final ValidatedPublisher validatedPublisher = mock(ValidatedPublisher.class);
    private final RejectPublisher rejectPublisher = mock(RejectPublisher.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RawMessageListener listener = new RawMessageListener(
            validator, validatedPublisher, rejectPublisher, meters, Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void mdcIsClean() {
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void validMessageIsPublishedToValidated() {
        ValidatedMessageV1 validated = validated();
        when(validator.validate(new TopicAddress("gw-emulator-01", "telemetry"), BODY))
                .thenReturn(validated);

        listener.onMessage(raw(ROUTING_KEY, TRACEPARENT));

        verify(validatedPublisher).publish(validated, TRACEPARENT);
        verifyNoInteractions(rejectPublisher);
        assertThat(count("TELEMETRY", "accepted", "none")).isEqualTo(1);
        assertThat(meters.get(RawMessageListener.VALIDATION_METRIC).timer().count())
                .isEqualTo(1);
    }

    @Test
    void contractViolationGoesToDlqAndIsAcked() {
        MessageRejectionException rejection = new MessageRejectionException(RejectReason.PAYLOAD_SCHEMA, "bad");
        when(validator.validate(any(), any())).thenThrow(rejection);
        Message raw = raw(ROUTING_KEY, null);

        listener.onMessage(raw); // не бросает → ack

        verify(rejectPublisher).publish(raw, rejection, "gw-emulator-01", NOW);
        verifyNoInteractions(validatedPublisher);
        assertThat(count("unknown", "rejected", "PAYLOAD_SCHEMA")).isEqualTo(1);
    }

    @Test
    void malformedRoutingKeyIsRejectedWithoutGateway() {
        Message raw = raw("senso.v1.gw..telemetry", null);

        listener.onMessage(raw);

        verify(rejectPublisher).publish(eq(raw), any(MessageRejectionException.class), isNull(), eq(NOW));
        verifyNoInteractions(validator, validatedPublisher);
        assertThat(count("unknown", "rejected", "ROUTING_KEY")).isEqualTo(1);
    }

    @Test
    void publishFailureIsRethrownForRedelivery() {
        when(validator.validate(any(), any())).thenReturn(validated());
        doThrow(new PublishFailedException("unroutable"))
                .when(validatedPublisher)
                .publish(any(), any());

        assertThatThrownBy(() -> listener.onMessage(raw(ROUTING_KEY, null))).isInstanceOf(PublishFailedException.class);

        verify(rejectPublisher, never()).publish(any(), any(), any(), any());
        assertThat(count("TELEMETRY", "error", "none")).isEqualTo(1);
        assertThat(meters.find(RawMessageListener.MESSAGES_METRIC)
                        .tag("result", "accepted")
                        .counter())
                .isNull();
    }

    @Test
    void dlqPublishFailureIsRethrownForRedelivery() {
        when(validator.validate(any(), any()))
                .thenThrow(new MessageRejectionException(RejectReason.MALFORMED_JSON, "bad"));
        doThrow(new PublishFailedException("nack")).when(rejectPublisher).publish(any(), any(), any(), any());

        assertThatThrownBy(() -> listener.onMessage(raw(ROUTING_KEY, null))).isInstanceOf(PublishFailedException.class);

        assertThat(count("unknown", "error", "none")).isEqualTo(1);
        assertThat(meters.find(RawMessageListener.MESSAGES_METRIC)
                        .tag("result", "rejected")
                        .counter())
                .isNull();
    }

    @Test
    void mdcCarriesGatewayMessageAndTraceWhilePublishing() {
        ValidatedMessageV1 validated = validated();
        when(validator.validate(any(), any())).thenReturn(validated);
        Map<String, String> seen = new HashMap<>();
        doAnswer(invocation -> {
                    seen.putAll(MDC.getCopyOfContextMap());
                    return null;
                })
                .when(validatedPublisher)
                .publish(any(), any());

        listener.onMessage(raw(ROUTING_KEY, TRACEPARENT));

        assertThat(seen)
                .containsEntry("gatewayId", "gw-emulator-01")
                .containsEntry("messageId", validated.envelope().messageId().toString())
                .containsEntry("traceId", "0af7651916cd43dd8448eb211c80319c");
    }

    @Test
    void traceIdIsTakenOnlyFromWellFormedTraceparent() {
        assertThat(RawMessageListener.traceId(TRACEPARENT)).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(RawMessageListener.traceId("garbage")).isNull();
        assertThat(RawMessageListener.traceId(null)).isNull();
    }

    private double count(String type, String result, String reason) {
        return meters.get(RawMessageListener.MESSAGES_METRIC)
                .tag("type", type)
                .tag("result", result)
                .tag("reason", reason)
                .counter()
                .count();
    }

    private static Message raw(String routingKey, String traceparent) {
        MessageProperties properties = new MessageProperties();
        properties.setReceivedRoutingKey(routingKey);
        if (traceparent != null) {
            properties.setHeader(Headers.TRACEPARENT, traceparent);
        }
        return new Message(BODY, properties);
    }

    private static ValidatedMessageV1 validated() {
        Envelope envelope = new Envelope(
                1,
                UUID.fromString("0195f3c2-7d1e-7a4b-9c11-2b6f0e5a8d34"),
                "gw-emulator-01",
                MessageType.TELEMETRY,
                NOW,
                "boot-0001",
                null,
                Map.of(),
                null);
        return new ValidatedMessageV1(envelope, NOW, "gw-emulator-01", false);
    }
}
