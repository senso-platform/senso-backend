package dev.senso.core.telemetry.internal.domain;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.NameBasedGenerator;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Временное сопоставление: детерминированный UUID v5 от {@code gatewayId:deviceId}, все устройства активны.
 *
 * <p>TODO S4: заменить на {@code devices::api} ({@code DeviceQueries.resolve}), когда в {@code devices} появятся
 * обнаружение устройств из {@code DEVICE_DISCOVERED} и их подтверждение владельцем. Сейчас ни одно устройство не
 * может стать {@code ACTIVE}, и настоящее сопоставление отбросило бы всю телеметрию. После замены строки,
 * записанные с этими UUID, к устройствам не привяжутся — это данные разработки.
 */
@Component
class StubDeviceResolver implements DeviceResolver {

    /** Фиксированное пространство имён UUID v5 для заглушки; не менять — изменятся id уже записанных устройств. */
    static final UUID NAMESPACE = UUID.fromString("5e6f0b1c-6a7d-4c2e-9b1f-3d8a2c4e7f10");

    private final NameBasedGenerator generator = Generators.nameBasedGenerator(NAMESPACE);

    @Override
    public Optional<ResolvedDevice> resolve(String gatewayExternalId, String deviceExternalId) {
        UUID deviceId = generator.generate(gatewayExternalId + ":" + deviceExternalId);
        return Optional.of(new ResolvedDevice(deviceId, true));
    }
}
