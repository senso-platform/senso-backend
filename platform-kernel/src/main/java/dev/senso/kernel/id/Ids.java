package dev.senso.kernel.id;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import java.util.UUID;

/** Single entry point for domain identifiers: UUID v7, generated in the application (AGENTS.md §4). */
public final class Ids {

    private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    private Ids() {}

    public static UUID newId() {
        return GENERATOR.generate();
    }
}
