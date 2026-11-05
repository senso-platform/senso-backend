package dev.senso.core.telemetry.internal.web;

import dev.senso.core.shared.role.ApiRole;
import dev.senso.core.telemetry.internal.persistence.Measurement;
import dev.senso.core.telemetry.internal.service.TelemetryQueryService;
import dev.senso.openapi.api.DebugApi;
import dev.senso.openapi.model.MeasurementDto;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * ВРЕМЕННЫЙ debug-эндпоинт для демо (контракт: тег {@code debug}, удаляется вместе с ним): последние значения
 * метрик устройства по внешним id шлюза и устройства.
 *
 * <p>Включён только там, где {@code senso.telemetry.debug-endpoint=true} — это задано лишь в
 * {@code application-local.yml}. {@code @Profile("local")} здесь не подходит: вместе с {@link ApiRole}
 * ({@code @Profile({"api","local"})}) Spring объединяет профили через «или», и эндпоинт поднялся бы в {@code api}.
 */
@RestController
@ApiRole
@ConditionalOnBooleanProperty("senso.telemetry.debug-endpoint")
class DebugTelemetryController implements DebugApi {

    private final TelemetryQueryService queryService;

    DebugTelemetryController(TelemetryQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public ResponseEntity<List<MeasurementDto>> getDebugLatestMeasurements(String gatewayId, String deviceId) {
        List<MeasurementDto> body = queryService.latest(gatewayId, deviceId).stream()
                .map(measurement -> toDto(deviceId, measurement))
                .toList();
        return ResponseEntity.ok(body);
    }

    private static MeasurementDto toDto(String deviceExternalId, Measurement measurement) {
        return new MeasurementDto()
                .deviceId(deviceExternalId)
                .metric(measurement.metric())
                .value(BigDecimal.valueOf(measurement.value()))
                .ts(measurement.ts().atOffset(ZoneOffset.UTC));
    }
}
