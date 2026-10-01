package dev.senso.archrules.compliant.devices.internal.worker;

import dev.senso.core.shared.role.WorkerRole;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.scheduling.annotation.Scheduled;

/** Образец AGENTS.md §3.9: листенер и планировщик в классе с @WorkerRole. */
@WorkerRole
public class TelemetryWorker {

    @RabbitListener(queues = "senso.telemetry.v1")
    public void onTelemetry(String payload) {}

    @Scheduled(fixedDelay = 60_000L)
    public void tick() {}
}
