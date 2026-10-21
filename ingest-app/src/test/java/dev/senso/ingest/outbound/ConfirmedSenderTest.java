package dev.senso.ingest.outbound;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import dev.senso.ingest.config.IngestProperties;
import java.time.Duration;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.CorrelationData.Confirm;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class ConfirmedSenderTest {

    private static final Message MESSAGE = new Message(new byte[] {1, 2, 3}, new MessageProperties());

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final ConfirmedSender sender = new ConfirmedSender(
            rabbitTemplate, new IngestProperties(262_144, Duration.ofMinutes(5), Duration.ofMillis(200), false));

    @Test
    void succeedsOnAckWithoutReturn() {
        onSend(correlation -> correlation.getFuture().complete(new Confirm(true, null)));

        assertThatCode(() -> sender.send("ex", "key", MESSAGE)).doesNotThrowAnyException();
    }

    @Test
    void failsWhenMessageIsReturnedAsUnroutable() {
        onSend(correlation -> {
            correlation.setReturned(new ReturnedMessage(MESSAGE, 312, "NO_ROUTE", "ex", "key"));
            correlation.getFuture().complete(new Confirm(true, null));
        });

        assertThatThrownBy(() -> sender.send("ex", "key", MESSAGE))
                .isInstanceOf(PublishFailedException.class)
                .hasMessageContaining("unroutable")
                .hasMessageContaining("NO_ROUTE");
    }

    @Test
    void failsOnNack() {
        onSend(correlation -> correlation.getFuture().complete(new Confirm(false, "queue full")));

        assertThatThrownBy(() -> sender.send("ex", "key", MESSAGE))
                .isInstanceOf(PublishFailedException.class)
                .hasMessageContaining("nacked")
                .hasMessageContaining("queue full");
    }

    @Test
    void failsWhenConfirmDoesNotArriveInTime() {
        onSend(correlation -> {});

        assertThatThrownBy(() -> sender.send("ex", "key", MESSAGE))
                .isInstanceOf(PublishFailedException.class)
                .hasMessageContaining("no publisher confirm");
    }

    @Test
    void failsWhenBrokerIsUnavailable() {
        doThrow(new AmqpConnectException(new java.net.ConnectException("refused")))
                .when(rabbitTemplate)
                .send(eq("ex"), eq("key"), any(Message.class), any(CorrelationData.class));

        assertThatThrownBy(() -> sender.send("ex", "key", MESSAGE))
                .isInstanceOf(PublishFailedException.class)
                .hasCauseInstanceOf(AmqpConnectException.class);
    }

    private void onSend(Consumer<CorrelationData> broker) {
        doAnswer(invocation -> {
                    broker.accept(invocation.getArgument(3));
                    return null;
                })
                .when(rabbitTemplate)
                .send(eq("ex"), eq("key"), any(Message.class), any(CorrelationData.class));
    }
}
