package dev.senso.core.telemetry.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Подсчёт вставленных строк; сама вставка с {@code ON CONFLICT} — в {@code TelemetryWriteIT}. */
class MeasurementWriterTest {

    @Test
    void countsOnlyInsertedRowsSkippingConflicts() {
        assertThat(MeasurementWriter.insertedRows(new int[] {1, 0, 1, 1})).isEqualTo(OptionalInt.of(3));
    }

    @Test
    void unknownCountWhenDriverGivesNoInfo() {
        assertThat(MeasurementWriter.insertedRows(new int[] {1, Statement.SUCCESS_NO_INFO}))
                .isEmpty();
    }

    @Test
    void emptyBatchDoesNotTouchDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        assertThat(new MeasurementWriter(jdbc).write(List.of(), Instant.parse("2026-11-05T10:00:00Z")))
                .isEqualTo(OptionalInt.of(0));
        verifyNoInteractions(jdbc);
    }
}
