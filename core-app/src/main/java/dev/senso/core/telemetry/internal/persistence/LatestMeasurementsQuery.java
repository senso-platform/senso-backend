package dev.senso.core.telemetry.internal.persistence;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Последнее значение каждой метрики устройства (debug-эндпоинт). */
@Repository
public class LatestMeasurementsQuery {

    static final String SQL = "SELECT DISTINCT ON (metric) ts, device_id, metric, value FROM telemetry.measurements "
            + "WHERE device_id = ? ORDER BY metric, ts DESC";

    private final JdbcTemplate jdbc;

    public LatestMeasurementsQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Measurement> latest(UUID deviceId) {
        return jdbc.query(
                SQL,
                (rs, rowNum) -> new Measurement(
                        rs.getObject("ts", Timestamp.class).toInstant(),
                        rs.getObject("device_id", UUID.class),
                        rs.getString("metric"),
                        rs.getDouble("value")),
                deviceId);
    }
}
