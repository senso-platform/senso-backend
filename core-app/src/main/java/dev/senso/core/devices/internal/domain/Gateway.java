package dev.senso.core.devices.internal.domain;

import dev.senso.core.devices.api.GatewayState;
import dev.senso.kernel.error.ValidationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "gateways", schema = "devices")
public class Gateway {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "external_id", nullable = false, length = 64, unique = true)
    private String externalId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "mqtt_password_hash", nullable = false, length = 255)
    private String mqttPasswordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GatewayState state;

    @Column(name = "boot_id", length = 64)
    private String bootId;

    @Column(name = "online_observed", nullable = false)
    private boolean onlineObserved;

    @Column(name = "status_sent_at")
    private Instant statusSentAt;

    @Column(name = "inventory_at")
    private Instant inventoryAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(length = 64)
    private String firmware;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Gateway() {}

    public Gateway(UUID id, UUID ownerId, String externalId, String name, String mqttPasswordHash, Instant now) {
        this.id = id;
        this.ownerId = ownerId;
        this.externalId = externalId;
        this.name = name;
        this.mqttPasswordHash = mqttPasswordHash;
        this.state = GatewayState.UNKNOWN;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void rename(String name, Instant now) {
        this.name = name;
        this.updatedAt = now;
    }

    /** OFFLINE can be a static Last Will whose sentAt predates the latest heartbeat. */
    public boolean applyStatus(GatewayState state, String bootId, Instant sentAt, String firmware, Instant receivedAt) {
        if (state == null || state == GatewayState.UNKNOWN) {
            throw new ValidationException("Invalid gateway state");
        }
        if (state == GatewayState.OFFLINE) {
            if (!onlineObserved || !Objects.equals(this.bootId, bootId) || this.state == GatewayState.OFFLINE) {
                return false;
            }
            this.state = GatewayState.OFFLINE;
            this.updatedAt = receivedAt;
            return true;
        }
        if (statusSentAt != null && !sentAt.isAfter(statusSentAt)) {
            // ONLINE may arrive after a newer DEGRADED from the same boot. Establish the session
            // for Last Will validation without replacing the more recent live state.
            if (state == GatewayState.ONLINE && Objects.equals(this.bootId, bootId) && !onlineObserved) {
                onlineObserved = true;
                this.updatedAt = receivedAt;
                return true;
            }
            return false;
        }
        onlineObserved = state == GatewayState.ONLINE || (Objects.equals(this.bootId, bootId) && onlineObserved);
        this.state = state;
        this.bootId = bootId;
        this.statusSentAt = sentAt;
        this.lastSeenAt = receivedAt;
        if (firmware != null) {
            this.firmware = firmware;
        }
        this.updatedAt = receivedAt;
        return true;
    }

    public void setInventoryAt(Instant inventoryAt) {
        this.inventoryAt = inventoryAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getName() {
        return name;
    }

    public String getMqttPasswordHash() {
        return mqttPasswordHash;
    }

    public GatewayState getState() {
        return state;
    }

    public String getBootId() {
        return bootId;
    }

    public Instant getStatusSentAt() {
        return statusSentAt;
    }

    public Instant getInventoryAt() {
        return inventoryAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public String getFirmware() {
        return firmware;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
