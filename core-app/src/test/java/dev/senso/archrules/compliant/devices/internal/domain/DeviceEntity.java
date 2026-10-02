package dev.senso.archrules.compliant.devices.internal.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** Образец AGENTS.md §3.4/§3.5: сущность в internal.domain своего модуля, без RabbitTemplate. */
@Entity
@Table(name = "device", schema = "devices")
public class DeviceEntity {

    private UUID id;

    public UUID id() {
        return id;
    }
}
