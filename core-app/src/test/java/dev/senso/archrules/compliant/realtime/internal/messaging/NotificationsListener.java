package dev.senso.archrules.compliant.realtime.internal.messaging;

import dev.senso.core.shared.role.ApiRole;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Образец AGENTS.md §3.9: единственный законный листенер роли api — realtime (анонимная очередь на
 * senso.notifications у каждого инстанса) и heartbeat SSE.
 */
@ApiRole
public class NotificationsListener {

    @RabbitListener(queues = "#{notificationsQueue.name}")
    public void onNotification(String payload) {}

    @Scheduled(fixedRate = 15_000L)
    public void heartbeat() {}
}
