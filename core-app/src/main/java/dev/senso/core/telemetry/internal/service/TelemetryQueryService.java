package dev.senso.core.telemetry.internal.service;

import dev.senso.core.telemetry.internal.domain.DeviceResolver;
import dev.senso.core.telemetry.internal.domain.ResolvedDevice;
import dev.senso.core.telemetry.internal.persistence.LatestMeasurementsQuery;
import dev.senso.core.telemetry.internal.persistence.Measurement;
import dev.senso.kernel.error.NotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Чтение телеметрии. Пока только последние значения для debug-эндпоинта (S2, задача 8). */
@Service
public class TelemetryQueryService {

    private final DeviceResolver deviceResolver;
    private final LatestMeasurementsQuery latestQuery;

    public TelemetryQueryService(DeviceResolver deviceResolver, LatestMeasurementsQuery latestQuery) {
        this.deviceResolver = deviceResolver;
        this.latestQuery = latestQuery;
    }

    /**
     * @throws NotFoundException устройство неизвестно или по нему нет измерений
     */
    @Transactional(readOnly = true)
    public List<Measurement> latest(String gatewayExternalId, String deviceExternalId) {
        ResolvedDevice device = deviceResolver
                .resolve(gatewayExternalId, deviceExternalId)
                .orElseThrow(() -> notFound(gatewayExternalId, deviceExternalId));
        List<Measurement> latest = latestQuery.latest(device.deviceId());
        if (latest.isEmpty()) {
            throw notFound(gatewayExternalId, deviceExternalId);
        }
        return latest;
    }

    private static NotFoundException notFound(String gatewayExternalId, String deviceExternalId) {
        return new NotFoundException(
                "No measurements for device '" + deviceExternalId + "' of gateway '" + gatewayExternalId + "'");
    }
}
