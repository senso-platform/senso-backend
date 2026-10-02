package dev.senso.archrules.violators.loose;

import jakarta.persistence.Entity;
import java.util.UUID;

/** Нарушитель AGENTS.md §3.4: JPA-сущность вне internal.domain своего модуля. */
@Entity
public class EntityWrongPlace {

    private UUID id;

    public UUID id() {
        return id;
    }
}
