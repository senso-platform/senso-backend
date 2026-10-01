package dev.senso.archrules.violators.worker;

import org.springframework.amqp.rabbit.annotation.RabbitListener;

/** Нарушитель AGENTS.md §3.9: AMQP-листенер в классе без роли (ни @WorkerRole, ни @ApiRole). */
public class ListenerOutsideWorker {

    @RabbitListener(queues = "senso.telemetry.v1")
    public void onTelemetry(String payload) {}
}
