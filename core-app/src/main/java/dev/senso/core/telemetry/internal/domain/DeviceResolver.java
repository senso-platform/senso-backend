package dev.senso.core.telemetry.internal.domain;

import java.util.Optional;

/**
 * Сопоставление устройства шлюза ({@code gatewayId} + {@code deviceId} из сообщения) с устройством платформы.
 * Пустой результат — устройство неизвестно, его телеметрия не сохраняется.
 */
public interface DeviceResolver {

    Optional<ResolvedDevice> resolve(String gatewayExternalId, String deviceExternalId);
}
