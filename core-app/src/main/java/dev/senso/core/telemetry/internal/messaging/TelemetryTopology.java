package dev.senso.core.telemetry.internal.messaging;

import dev.senso.contracts.messaging.Exchanges;
import dev.senso.contracts.messaging.Queues;
import dev.senso.contracts.messaging.RoutingKeys;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Очередь записи телеметрии (AGENTS.md §6: топологию объявляет потребитель). Бины без роли: объявление
 * идемпотентно и выполняется и в {@code api}, и в {@code worker}, а листенер есть только в {@code worker}.
 *
 * <p>Обменники повторяют параметры из deploy/rabbitmq/definitions.json — иначе брокер ответит
 * {@code PRECONDITION_FAILED}; в тестовом брокере без definitions они создаются здесь.
 */
@Configuration(proxyBeanMethods = false)
class TelemetryTopology {

    /** Защита от падения worker-а посреди обработки; повторы при ошибках — retry листенера (application.yml). */
    static final int DELIVERY_LIMIT = 5;

    @Bean
    Declarables telemetryDeclarables() {
        TopicExchange validated = new TopicExchange(Exchanges.VALIDATED, true, false);
        DirectExchange dlq = new DirectExchange(Exchanges.DLQ, true, false);
        Queue writer = QueueBuilder.durable(Queues.TELEMETRY_WRITER)
                .quorum()
                .deadLetterExchange(Exchanges.DLQ)
                .deadLetterRoutingKey(RoutingKeys.DLQ_FAILED)
                .deliveryLimit(DELIVERY_LIMIT)
                .build();
        return new Declarables(
                validated,
                dlq,
                writer,
                BindingBuilder.bind(writer).to(validated).with(RoutingKeys.TELEMETRY_V1));
    }
}
