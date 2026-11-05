package dev.senso.core.telemetry.internal.service;

import dev.senso.contracts.internal.ValidatedMessageV1;
import dev.senso.contracts.mqtt.payload.TelemetryPayload;
import dev.senso.core.telemetry.internal.domain.DeviceResolver;
import dev.senso.core.telemetry.internal.domain.ResolvedDevice;
import dev.senso.core.telemetry.internal.persistence.Measurement;
import dev.senso.core.telemetry.internal.persistence.MeasurementWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Запись телеметрии из {@code senso.validated}: одно сообщение — одна транзакция — один {@code batchUpdate} всех
 * сэмплов. Пакетирование между сообщениями не делаем (оптимизация для нагрузочного теста S5).
 */
@Service
public class TelemetryWriteService {

    static final String WRITTEN = "telemetry.written";
    static final String DROPPED = "telemetry.dropped";
    static final String WRITE_TIMER = "telemetry.write";
    static final String UNKNOWN_DEVICE = "unknown_device";
    static final String INACTIVE_DEVICE = "inactive_device";

    private static final Logger log = LoggerFactory.getLogger(TelemetryWriteService.class);

    private final DeviceResolver deviceResolver;
    private final MeasurementWriter writer;
    private final ObjectMapper objectMapper;
    private final Counter written;
    private final Counter droppedUnknown;
    private final Counter droppedInactive;
    private final Timer writeTimer;

    public TelemetryWriteService(
            DeviceResolver deviceResolver,
            MeasurementWriter writer,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.deviceResolver = deviceResolver;
        this.writer = writer;
        this.objectMapper = objectMapper;
        this.written = Counter.builder(WRITTEN)
                .description("Строки, записанные в telemetry.measurements (без дублей)")
                .register(meterRegistry);
        this.droppedUnknown = dropped(meterRegistry, UNKNOWN_DEVICE);
        this.droppedInactive = dropped(meterRegistry, INACTIVE_DEVICE);
        this.writeTimer = Timer.builder(WRITE_TIMER)
                .description("Время записи одного сообщения телеметрии")
                .register(meterRegistry);
    }

    /**
     * @return итог записи; исключение откатывает транзакцию, и листенер повторяет сообщение целиком
     */
    @Transactional
    public WriteResult write(ValidatedMessageV1 message) {
        TelemetryPayload payload = objectMapper.convertValue(message.envelope().payload(), TelemetryPayload.class);
        List<Measurement> rows = new ArrayList<>();
        int unknown = 0;
        int inactive = 0;
        for (TelemetryPayload.Sample sample : payload.samples()) {
            Optional<ResolvedDevice> device = deviceResolver.resolve(message.gatewayId(), sample.deviceId());
            int values = sample.metrics().size();
            if (device.isEmpty()) {
                unknown += values;
            } else if (!device.get().active()) {
                inactive += values;
            } else {
                for (Map.Entry<String, Double> metric : sample.metrics().entrySet()) {
                    rows.add(new Measurement(sample.ts(), device.get().deviceId(), metric.getKey(), metric.getValue()));
                }
            }
        }

        OptionalInt inserted = writeTimer.record(() -> writer.write(rows, message.receivedAt()));
        inserted.ifPresent(written::increment);
        droppedUnknown.increment(unknown);
        droppedInactive.increment(inactive);

        WriteResult result = new WriteResult(rows.size(), inserted, unknown, inactive);
        log.debug(
                "Telemetry written: messageId={} rows={} inserted={} droppedUnknown={} droppedInactive={}",
                message.envelope().messageId(),
                result.rows(),
                inserted.isPresent() ? inserted.getAsInt() : "n/a",
                unknown,
                inactive);
        return result;
    }

    private static Counter dropped(MeterRegistry registry, String reason) {
        return Counter.builder(DROPPED)
                .description("Значения телеметрии, не записанные из-за устройства")
                .tag("reason", reason)
                .register(registry);
    }

    /**
     * @param rows строк отправлено в БД
     * @param inserted из них реально вставлено (дубли не считаются); пусто, если драйвер не сообщил
     * @param droppedUnknown значений от неизвестных устройств
     * @param droppedInactive значений от неактивных устройств
     */
    public record WriteResult(int rows, OptionalInt inserted, int droppedUnknown, int droppedInactive) {}
}
