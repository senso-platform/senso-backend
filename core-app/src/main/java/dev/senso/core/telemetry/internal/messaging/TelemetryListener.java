package dev.senso.core.telemetry.internal.messaging;

import dev.senso.contracts.internal.ValidatedMessageV1;
import dev.senso.contracts.messaging.Queues;
import dev.senso.core.shared.role.WorkerRole;
import dev.senso.core.telemetry.internal.service.TelemetryWriteService;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Приём проверенной телеметрии ({@code senso.validated} / {@code telemetry.v1}). Подтверждение — auto: ack после
 * возврата из метода, то есть после коммита транзакции записи. Исключение → повторы листенера → {@code q.dlq.failed}.
 */
@Component
@WorkerRole
class TelemetryListener {

    private final TelemetryWriteService writeService;

    TelemetryListener(TelemetryWriteService writeService) {
        this.writeService = writeService;
    }

    @RabbitListener(queues = Queues.TELEMETRY_WRITER)
    void onMessage(ValidatedMessageV1 message) {
        MDC.put("gatewayId", message.gatewayId());
        MDC.put("messageId", String.valueOf(message.envelope().messageId()));
        try {
            writeService.write(message);
        } finally {
            MDC.remove("gatewayId");
            MDC.remove("messageId");
        }
    }
}
