package dev.senso.core.telemetry.internal.persistence;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalInt;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Горячий путь записи телеметрии (AGENTS.md §4): один {@code batchUpdate} на сообщение, без JPA. Повтор того же
 * измерения ({@code device_id, metric, ts} — первичный ключ) молча пропускается, поэтому приёмник идемпотентен.
 */
@Repository
public class MeasurementWriter {

    static final String INSERT = "INSERT INTO telemetry.measurements (ts, device_id, metric, value, received_at) "
            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";

    private final JdbcTemplate jdbc;

    public MeasurementWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return сколько строк реально вставлено (без дублей); пусто, если драйвер не сообщил счётчики
     *     ({@link Statement#SUCCESS_NO_INFO}, например при {@code reWriteBatchedInserts=true}) — тогда число неизвестно
     */
    public OptionalInt write(List<Measurement> rows, Instant receivedAt) {
        if (rows.isEmpty()) {
            return OptionalInt.of(0);
        }
        int[] counts = jdbc.batchUpdate(INSERT, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Measurement row = rows.get(i);
                // pgjdbc не принимает Instant напрямую; OffsetDateTime в UTC пишется в timestamptz без сдвигов.
                ps.setObject(1, row.ts().atOffset(ZoneOffset.UTC));
                ps.setObject(2, row.deviceId());
                ps.setString(3, row.metric());
                ps.setDouble(4, row.value());
                ps.setObject(5, receivedAt.atOffset(ZoneOffset.UTC));
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
        return insertedRows(counts);
    }

    static OptionalInt insertedRows(int[] counts) {
        int inserted = 0;
        for (int count : counts) {
            if (count == Statement.SUCCESS_NO_INFO) {
                return OptionalInt.empty();
            }
            inserted += Math.max(count, 0);
        }
        return OptionalInt.of(inserted);
    }
}
