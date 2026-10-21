package dev.senso.ingest.config;

import dev.senso.contracts.messaging.Exchanges;
import dev.senso.contracts.messaging.Queues;
import dev.senso.contracts.messaging.RoutingKeys;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Binding.DestinationType;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Топология, которую ingest потребляет и в которую публикует (AGENTS.md §6). Аргументы обязаны совпадать с
 * deploy/rabbitmq/definitions.json, иначе брокер отвечает {@code PRECONDITION_FAILED}.
 */
@Configuration(proxyBeanMethods = false)
public class IngestTopology {

    /** Сколько раз брокер доставит «ядовитое» сообщение, прежде чем увести его в DLX ({@code q.dlq.failed}). */
    static final int RAW_DELIVERY_LIMIT = 5;

    @Bean
    Declarables ingestDeclarables() {
        Queue raw = QueueBuilder.durable(Queues.INGEST_RAW)
                .quorum()
                .deadLetterExchange(Exchanges.DLQ)
                .deadLetterRoutingKey(RoutingKeys.DLQ_FAILED)
                .deliveryLimit(RAW_DELIVERY_LIMIT)
                .build();
        // amq.topic — системный обменник, объявлять его нельзя: только binding без бина обменника.
        Binding mqttToRaw =
                new Binding(Queues.INGEST_RAW, DestinationType.QUEUE, Exchanges.AMQ_TOPIC, RoutingKeys.MQTT_ALL, null);
        return new Declarables(
                raw,
                mqttToRaw,
                new TopicExchange(Exchanges.VALIDATED, true, false),
                new DirectExchange(Exchanges.DLQ, true, false));
    }
}
