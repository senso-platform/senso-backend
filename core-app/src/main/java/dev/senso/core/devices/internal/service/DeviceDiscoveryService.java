package dev.senso.core.devices.internal.service;

import dev.senso.contracts.mqtt.payload.DeviceDiscoveredPayload;
import dev.senso.contracts.mqtt.payload.DeviceInventoryPayload;
import dev.senso.contracts.mqtt.payload.GatewayStatusPayload;
import dev.senso.core.devices.api.DeviceCapability;
import dev.senso.core.devices.api.DeviceStatus;
import dev.senso.core.devices.api.GatewayState;
import dev.senso.core.devices.internal.domain.Device;
import dev.senso.core.devices.internal.domain.Gateway;
import dev.senso.core.devices.internal.repository.DeviceRepository;
import dev.senso.core.devices.internal.repository.GatewayRepository;
import dev.senso.kernel.error.ConflictException;
import dev.senso.kernel.error.NotFoundException;
import dev.senso.kernel.error.ValidationException;
import dev.senso.kernel.id.Ids;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application operations for schema-validated gateway observations. This is not a broker consumer:
 * ingest validation and its AMQP adapter must be connected before accepting external traffic.
 * All mutations serialize on the gateway row, including owner commands in {@link DeviceService}.
 */
@Service
public class DeviceDiscoveryService {

    private static final int MAX_PENDING = 50;

    private final GatewayRepository gateways;
    private final DeviceRepository devices;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final MeterRegistry meters;

    public DeviceDiscoveryService(
            GatewayRepository gateways,
            DeviceRepository devices,
            JdbcTemplate jdbc,
            Clock clock,
            MeterRegistry meters) {
        this.gateways = gateways;
        this.devices = devices;
        this.jdbc = jdbc;
        this.clock = clock;
        this.meters = meters;
    }

    @Transactional
    public void discover(String gatewayExternalId, UUID messageId, Instant sentAt, DeviceDiscoveredPayload payload) {
        Gateway gateway = gateway(gatewayExternalId);
        if (!firstDelivery(gateway, messageId)) {
            return;
        }
        if (gateway.getInventoryAt() != null && !sentAt.isAfter(gateway.getInventoryAt())) {
            count("discovery", "stale");
            return;
        }
        discover(gateway, sentAt, payload);
        count("discovery", "applied");
    }

    @Transactional
    public void inventory(String gatewayExternalId, UUID messageId, Instant sentAt, DeviceInventoryPayload payload) {
        Gateway gateway = gateway(gatewayExternalId);
        if (!firstDelivery(gateway, messageId)) {
            return;
        }
        if (gateway.getInventoryAt() != null && !sentAt.isAfter(gateway.getInventoryAt())) {
            count("inventory", "stale");
            return;
        }
        Set<String> present = new HashSet<>();
        for (DeviceInventoryPayload.Entry entry : payload.devices()) {
            if (!present.add(entry.deviceId())) {
                throw new ValidationException("Inventory contains duplicate device ids");
            }
            discover(
                    gateway,
                    sentAt,
                    new DeviceDiscoveredPayload(entry.deviceId(), entry.deviceType(), entry.capabilities(), null));
        }
        Instant now = clock.instant();
        for (Device device : devices.findAllByGatewayId(gateway.getId())) {
            if (!present.contains(device.getExternalId())) {
                device.disconnect(sentAt, now);
            }
        }
        gateway.setInventoryAt(sentAt);
        count("inventory", "applied");
    }

    /** LWT has a static message id and sentAt; it must bypass discovery/inventory deduplication. */
    @Transactional
    public void updateGatewayStatus(
            String gatewayExternalId, String bootId, Instant sentAt, GatewayStatusPayload payload) {
        Gateway gateway = gateway(gatewayExternalId);
        GatewayState state;
        try {
            state = GatewayState.valueOf(payload.state());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ValidationException("Unsupported gateway state", exception);
        }
        if (state == GatewayState.UNKNOWN) {
            throw new ValidationException("Unsupported gateway state");
        }
        boolean applied = gateway.applyStatus(state, bootId, sentAt, payload.firmware(), clock.instant());
        count("gateway_status", applied ? "applied" : "stale");
    }

    private Gateway gateway(String externalId) {
        return gateways.lockByExternalId(externalId).orElseThrow(() -> new NotFoundException("Gateway not found"));
    }

    private boolean firstDelivery(Gateway gateway, UUID messageId) {
        int inserted = jdbc.update("""
                INSERT INTO devices.processed_messages (gateway_id, message_id, processed_at)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, gateway.getId(), messageId, Timestamp.from(clock.instant()));
        if (inserted == 0) {
            count("observation", "duplicate");
        }
        return inserted == 1;
    }

    private void discover(Gateway gateway, Instant observedAt, DeviceDiscoveredPayload payload) {
        Device device = devices.findByGatewayIdAndExternalId(gateway.getId(), payload.deviceId())
                .orElse(null);
        if (device != null
                && device.getConnectivityObservedAt() != null
                && observedAt.isBefore(device.getConnectivityObservedAt())) {
            return;
        }
        if (device == null || device.getStatus() == DeviceStatus.REJECTED) {
            if (devices.countByGatewayIdAndStatus(gateway.getId(), DeviceStatus.PENDING) >= MAX_PENDING) {
                throw new ConflictException("Gateway pending device limit reached");
            }
        }
        List<DeviceCapability> capabilities = payload.capabilities() == null
                ? List.of()
                : payload.capabilities().stream()
                        .map(capability -> new DeviceCapability(capability.metric(), capability.unit()))
                        .toList();
        // Inventory omits metadata. Preserve the previously discovered document in that case.
        Map<String, Object> metadata =
                payload.metadata() == null ? device == null ? Map.of() : device.getMetadata() : payload.metadata();
        Instant now = clock.instant();
        if (device == null) {
            device = new Device(
                    Ids.newId(),
                    gateway.getId(),
                    payload.deviceId(),
                    payload.deviceType(),
                    capabilities,
                    metadata,
                    now);
            device = devices.save(device);
        }
        device.discover(payload.deviceType(), capabilities, metadata, observedAt, now);
    }

    private void count(String operation, String result) {
        meters.counter("devices.observations", "operation", operation, "result", result)
                .increment();
    }
}
