package dev.senso.archrules.violators.devices.internal.domain;

import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Нарушитель AGENTS.md §3.7: класс вне internal.messaging держит RabbitTemplate вместо outbox. */
public class DomainUsesRabbitTemplate {

    private final RabbitTemplate rabbitTemplate;

    public DomainUsesRabbitTemplate(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public RabbitTemplate rabbitTemplate() {
        return rabbitTemplate;
    }
}
