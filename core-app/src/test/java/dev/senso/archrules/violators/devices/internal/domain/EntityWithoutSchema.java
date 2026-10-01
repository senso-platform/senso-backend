package dev.senso.archrules.violators.devices.internal.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** Нарушитель AGENTS.md §3.5: сущность на месте, но без схемы модуля — таблица уйдёт в схему по умолчанию. */
@Entity
@Table(name = "gateway")
public class EntityWithoutSchema {

    private UUID id;

    public UUID id() {
        return id;
    }
}
