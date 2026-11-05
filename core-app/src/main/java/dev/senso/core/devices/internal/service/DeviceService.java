package dev.senso.core.devices.internal.service;

import dev.senso.core.devices.api.DeviceQueries;
import dev.senso.core.devices.api.DeviceStatus;
import dev.senso.core.devices.api.DeviceView;
import dev.senso.core.devices.internal.domain.Device;
import dev.senso.core.devices.internal.domain.Gateway;
import dev.senso.core.devices.internal.mapping.DeviceMapper;
import dev.senso.core.devices.internal.repository.DeviceRepository;
import dev.senso.core.devices.internal.repository.GatewayRepository;
import dev.senso.kernel.error.ConflictException;
import dev.senso.kernel.error.NotFoundException;
import dev.senso.kernel.error.ValidationException;
import dev.senso.kernel.page.Cursor;
import dev.senso.kernel.page.CursorPage;
import dev.senso.kernel.page.PageRequestParams;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ownership is enforced here; ownerId must come from a trusted authenticated caller. */
@Service
public class DeviceService implements DeviceQueries {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceRepository devices;
    private final GatewayRepository gateways;
    private final DeviceMapper mapper;
    private final Clock clock;

    public DeviceService(DeviceRepository devices, GatewayRepository gateways, DeviceMapper mapper, Clock clock) {
        this.devices = devices;
        this.gateways = gateways;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Trusted worker lookup: only approved, enabled devices can accept telemetry. */
    @Override
    @Transactional(readOnly = true)
    public Optional<DeviceView> resolve(String gatewayExternalId, String deviceExternalId) {
        return gateways.findByExternalId(gatewayExternalId)
                .flatMap(gateway -> devices.findByGatewayIdAndExternalId(gateway.getId(), deviceExternalId))
                .filter(device -> device.getStatus() == DeviceStatus.ACTIVE)
                .map(mapper::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public DeviceView find(UUID ownerId, UUID deviceId) {
        if (deviceId == null) {
            throw new NotFoundException("Device not found");
        }
        Device device = devices.findById(deviceId).orElseThrow(() -> new NotFoundException("Device not found"));
        ownedGateway(ownerId, device.getGatewayId());
        return mapper.toView(device);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPage<DeviceView> list(UUID ownerId, UUID gatewayId, PageRequestParams params) {
        if (ownerId == null) {
            throw new NotFoundException("User not found");
        }
        if (gatewayId != null) {
            ownedGateway(ownerId, gatewayId);
        }
        Cursor cursor = params.decodeCursor();
        var rows = devices.findPage(
                ownerId,
                gatewayId,
                cursor == null ? null : cursor.timestamp(),
                cursor == null ? null : cursor.id(),
                params.fetchSize());
        return CursorPage.of(
                rows.stream().map(mapper::toView).toList(),
                params.limit(),
                row -> new Cursor(row.createdAt(), row.id()));
    }

    @Transactional
    public DeviceView approve(UUID ownerId, UUID deviceId) {
        return changeStatus(ownerId, deviceId, DeviceStatus.PENDING, DeviceStatus.ACTIVE);
    }

    @Transactional
    public DeviceView reject(UUID ownerId, UUID deviceId) {
        return changeStatus(ownerId, deviceId, DeviceStatus.PENDING, DeviceStatus.REJECTED);
    }

    @Transactional
    public DeviceView disable(UUID ownerId, UUID deviceId) {
        return changeStatus(ownerId, deviceId, DeviceStatus.ACTIVE, DeviceStatus.DISABLED);
    }

    @Transactional
    public DeviceView enable(UUID ownerId, UUID deviceId) {
        return changeStatus(ownerId, deviceId, DeviceStatus.DISABLED, DeviceStatus.ACTIVE);
    }

    @Transactional
    public DeviceView rename(UUID ownerId, UUID deviceId, String name) {
        Device device = lockedOwnedDevice(ownerId, deviceId);
        if (name == null || name.isBlank() || name.length() > 128) {
            throw new ValidationException("Device name must contain between 1 and 128 characters");
        }
        device.rename(name, clock.instant());
        return mapper.toView(device);
    }

    private DeviceView changeStatus(UUID ownerId, UUID deviceId, DeviceStatus source, DeviceStatus target) {
        Device device = lockedOwnedDevice(ownerId, deviceId);
        if (device.getStatus() != source && device.getStatus() != target) {
            throw new ConflictException("Device cannot perform this action in its current status");
        }
        DeviceStatus previous = device.getStatus();
        device.changeStatus(target, ownerId, clock.instant());
        if (previous != target) {
            try (var user = MDC.putCloseable("userId", ownerId.toString());
                    var gateway =
                            MDC.putCloseable("gatewayId", device.getGatewayId().toString());
                    var id = MDC.putCloseable("deviceId", deviceId.toString())) {
                log.info("Device status changed from {} to {}", previous, target);
            }
        }
        return mapper.toView(device);
    }

    private Device lockedOwnedDevice(UUID ownerId, UUID deviceId) {
        if (deviceId == null) {
            throw new NotFoundException("Device not found");
        }
        Gateway gateway =
                gateways.lockByDeviceId(deviceId).orElseThrow(() -> new NotFoundException("Device not found"));
        if (!gateway.getOwnerId().equals(ownerId)) {
            throw new NotFoundException("Device not found");
        }
        return devices.findById(deviceId).orElseThrow(() -> new NotFoundException("Device not found"));
    }

    private Gateway ownedGateway(UUID ownerId, UUID gatewayId) {
        if (ownerId == null) {
            throw new NotFoundException("Gateway not found");
        }
        return gateways.findByIdAndOwnerId(gatewayId, ownerId)
                .orElseThrow(() -> new NotFoundException("Gateway not found"));
    }
}
