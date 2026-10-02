package dev.senso.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

@ActiveProfiles("api")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CoreApplicationIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @LocalServerPort
    int port;

    @Test
    @SuppressWarnings("unchecked")
    void contextLoadsAndHealthIsUp() {
        Map<String, Object> health = RestClient.create()
                .get()
                .uri("http://localhost:%d/actuator/health".formatted(port))
                .retrieve()
                .body(Map.class);
        assertThat(health).isNotNull();
        assertThat(health.get("status")).isEqualTo("UP");
    }

    @Test
    void flywayAppliesPlatformMigrations() {
        assertThat(jdbc.queryForList(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'platform'",
                        String.class))
                .contains("event_publication", "shedlock", "flyway_schema_history");
    }

    @Test
    void extensionsAreCreatedByMigrationNotByDockerInit() {
        // Контейнер тестов не получает deploy/postgres/init: расширения обязана создать миграция platform_extensions.
        assertThat(jdbc.queryForList("SELECT extname FROM pg_extension", String.class))
                .contains("timescaledb", "citext");
    }

    @Test
    void modulithEndpointIsNotExposedOnApiPort() {
        int status = RestClient.create()
                .get()
                .uri("http://localhost:%d/actuator/modulith".formatted(port))
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(status).isEqualTo(404);
    }

    @Test
    void telemetrySchemaIsHypertableWithAggregates() {
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM timescaledb_information.hypertables"
                                + " WHERE hypertable_schema = 'telemetry' AND hypertable_name = 'measurements'",
                        Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForList(
                        "SELECT view_name FROM timescaledb_information.continuous_aggregates WHERE view_schema = 'telemetry'",
                        String.class))
                .contains("measurements_1m", "measurements_1h");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM timescaledb_information.jobs"
                                + " WHERE application_name LIKE 'Refresh Continuous Aggregate Policy%'",
                        Long.class))
                .isEqualTo(2L);
    }
}
