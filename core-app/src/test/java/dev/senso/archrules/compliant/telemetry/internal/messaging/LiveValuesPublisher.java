package dev.senso.archrules.compliant.telemetry.internal.messaging;

import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Образец AGENTS.md §3.7: прямая публикация (живая телеметрия senso.live) — только из internal.messaging. */
public class LiveValuesPublisher {

    private final RabbitTemplate rabbitTemplate;

    public LiveValuesPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public RabbitTemplate rabbitTemplate() {
        return rabbitTemplate;
    }
}
