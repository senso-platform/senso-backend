package dev.senso.core.devices.internal.domain;

import dev.senso.core.devices.api.Connectivity;
import dev.senso.core.devices.api.DeviceCapability;
import dev.senso.core.devices.api.DeviceStatus;
import dev.senso.kernel.error.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "devices", schema = "devices")
public class Device {

    @Id
    private UUID id;

    @Column(name = "gateway_id", nullable = false)
    private UUID gatewayId;

    @Column(name = "external_id", nullable = false, length = 64)
    private String externalId;

    @Column(length = 128)
    private String name;

    @Column(name = "device_type", nullable = false, length = 32)
    private String deviceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DeviceStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<DeviceCapability> capabilities;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Connectivity connectivity;

    @Column(name = "connectivity_observed_at")
    private Instant connectivityObservedAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Device() {}

    public Device(
            UUID id,
            UUID gatewayId,
            String externalId,
            String deviceType,
            List<DeviceCapability> capabilities,
            Map<String, Object> metadata,
            Instant now) {
        this.id = id;
        this.gatewayId = gatewayId;
        this.externalId = externalId;
        this.deviceType = deviceType;
        this.capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        this.metadata = copyMetadata(metadata);
        this.status = DeviceStatus.PENDING;
        this.connectivity = Connectivity.UNKNOWN;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void discover(
            String deviceType,
            List<DeviceCapability> capabilities,
            Map<String, Object> metadata,
            Instant observedAt,
            Instant now) {
        if (connectivityObservedAt != null && observedAt.isBefore(connectivityObservedAt)) {
            return;
        }
        this.deviceType = deviceType;
        this.capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        if (metadata != null) {
            this.metadata = copyMetadata(metadata);
        }
        this.connectivity = Connectivity.ONLINE;
        this.connectivityObservedAt = observedAt;
        this.lastSeenAt = observedAt;
        if (status == DeviceStatus.REJECTED) {
            this.status = DeviceStatus.PENDING;
        }
        this.updatedAt = now;
    }

    public void changeStatus(DeviceStatus target, UUID actor, Instant now) {
        if (status == target) {
            return;
        }
        boolean allowed = switch (status) {
            case PENDING -> target == DeviceStatus.ACTIVE || target == DeviceStatus.REJECTED;
            case ACTIVE -> target == DeviceStatus.DISABLED;
            case DISABLED -> target == DeviceStatus.ACTIVE;
            case REJECTED -> false;
        };
        if (!allowed) {
            throw new ConflictException("Invalid device status transition: " + status + " -> " + target);
        }
        if (status == DeviceStatus.PENDING && target == DeviceStatus.ACTIVE) {
            this.approvedBy = actor;
            this.approvedAt = now;
        }
        this.status = target;
        this.updatedAt = now;
    }

    public void rename(String name, Instant now) {
        this.name = name;
        this.updatedAt = now;
    }

    /** An inventory omission is an observation, not an actual sighting of the device. */
    public void disconnect(Instant observedAt, Instant now) {
        if (connectivityObservedAt != null && observedAt.isBefore(connectivityObservedAt)) {
            return;
        }
        this.connectivity = Connectivity.OFFLINE;
        this.connectivityObservedAt = observedAt;
        this.updatedAt = now;
    }

    private static Map<String, Object> copyMetadata(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> copy.put(key, copyValue(value)));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put((String) key, copyValue(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(copyValue(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    public UUID getId() {
        return id;
    }

    public UUID getGatewayId() {
        return gatewayId;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getName() {
        return name;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public DeviceStatus getStatus() {
        return status;
    }

    public List<DeviceCapability> getCapabilities() {
        return capabilities;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Connectivity getConnectivity() {
        return connectivity;
    }

    public Instant getConnectivityObservedAt() {
        return connectivityObservedAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
