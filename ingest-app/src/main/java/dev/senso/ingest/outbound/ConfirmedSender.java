package dev.senso.ingest.outbound;

import dev.senso.ingest.config.IngestProperties;
import dev.senso.kernel.id.Ids;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.CorrelationData.Confirm;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Синхронная публикация с publisher confirm: метод возвращается, только когда брокер подтвердил приём и сообщение
 * легло хотя бы в одну очередь. Успех = {@code ack == true} и {@code getReturned() == null} (template с
 * {@code mandatory=true}; Spring AMQP выставляет returned до завершения future confirm-а).
 */
@Component
public class ConfirmedSender {

    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;

    public ConfirmedSender(RabbitTemplate rabbitTemplate, IngestProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = properties.confirmTimeout();
    }

    public void send(String exchange, String routingKey, Message message) {
        CorrelationData correlation = new CorrelationData(Ids.newId().toString());
        String target = exchange + "/" + routingKey;
        Confirm confirm;
        try {
            rabbitTemplate.send(exchange, routingKey, message, correlation);
            confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (AmqpException e) {
            throw new PublishFailedException("publish to " + target + " failed", e);
        } catch (TimeoutException e) {
            throw new PublishFailedException("no publisher confirm for " + target + " within " + confirmTimeout, e);
        } catch (ExecutionException e) {
            throw new PublishFailedException("publisher confirm for " + target + " failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishFailedException("interrupted while waiting for confirm of " + target, e);
        }
        if (!confirm.ack()) {
            throw new PublishFailedException("broker nacked publish to " + target + ": " + confirm.reason());
        }
        ReturnedMessage returned = correlation.getReturned();
        if (returned != null) {
            throw new PublishFailedException("message to " + target + " is unroutable: " + returned.getReplyCode() + " "
                    + returned.getReplyText());
        }
    }
}
