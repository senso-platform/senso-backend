package dev.senso.core.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.senso.contracts.internal.ValidatedMessageV1;
import dev.senso.contracts.messaging.Exchanges;
import dev.senso.contracts.messaging.RoutingKeys;
import dev.senso.contracts.mqtt.Envelope;
import dev.senso.core.IntegrationTestBase;
import dev.senso.core.telemetry.internal.domain.DeviceResolver;
import dev.senso.core.telemetry.internal.persistence.Measurement;
import dev.senso.core.telemetry.internal.persistence.MeasurementWriter;
import dev.senso.kernel.id.Ids;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Запись телеметрии из {@code senso.validated} в {@code telemetry.measurements} (SCRUM-110, задача 7 плана S2)
 * и debug-эндпоинт последних значений (задача 8). Профиль {@code local}: в одном процессе и листенер (worker), и
 * контроллер (api). Каждый тест пишет от своего {@code gatewayId}, поэтому строки тестов не пересекаются.
 *
 * <p>{@code @DirtiesContext}: контекст с AMQP-листенером не оставляем в кэше — иначе он забирал бы сообщения
 * других тестов из общей очереди {@code q.telemetry.writer}.
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.rabbitmq.listener.simple.retry.initial-interval=50ms",
            "spring.rabbitmq.listener.simple.retry.max-interval=100ms"
        })
@DirtiesContext
class TelemetryWriteIT extends IntegrationTestBase {

    private static final Path EXAMPLE = Path.of("..", "contracts", "mqtt", "examples", "valid", "telemetry.json");
    private static final Duration WAIT = Duration.ofSeconds(15);

    /** Сэмплы примера: два устройства по две метрики. */
    private static final Map<String, Map<String, Double>> EXPECTED = Map.of(
            "sensor-fridge-01", Map.of("temperature", 4.2, "humidity", 41.0),
            "sensor-hall-02", Map.of("temperature", 21.7, "co2_ppm", 640.0));

    @MockitoSpyBean
    MeasurementWriter writer;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DeviceResolver deviceResolver;

    @Autowired
    JsonMapper jsonMapper;

    @LocalServerPort
    int port;

    private String gatewayId;
    private Set<UUID> deviceIds;

    @BeforeEach
    void uniqueGateway() {
        gatewayId = "gw-it-" + Ids.newId().toString().substring(0, 8);
        deviceIds = Set.of(device("sensor-fridge-01"), device("sensor-hall-02"));
    }

    @Test
    void everyMetricOfEverySampleIsWritten() {
        publish(message());

        await().atMost(WAIT).untilAsserted(() -> assertThat(rowCount()).isEqualTo(4));
        assertThat(metricValues(jdbc.queryForList(
                        "SELECT metric, value FROM telemetry.measurements WHERE device_id = ?",
                        device("sensor-fridge-01"))))
                .isEqualTo(EXPECTED.get("sensor-fridge-01"));
    }

    @Test
    void redeliveredMessageDoesNotCreateDuplicates() {
        ValidatedMessageV1 message = message();

        publish(message);
        await().atMost(WAIT).untilAsserted(() -> assertThat(rowCount()).isEqualTo(4));
        publish(message);

        verify(writer, timeout(WAIT.toMillis()).times(2)).write(argThat(this::ownRows), any());
        assertThat(rowCount()).isEqualTo(4);
    }

    @Test
    void failedWriteIsRetriedAndStoredExactlyOnce() {
        doThrow(new TransientDataAccessResourceException("simulated database failure"))
                .doCallRealMethod()
                .when(writer)
                .write(argThat(this::ownRows), any());

        publish(message());

        await().atMost(WAIT).untilAsserted(() -> assertThat(rowCount()).isEqualTo(4));
        verify(writer, times(2)).write(argThat(this::ownRows), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void debugEndpointShowsLatestValuesUnderApiPrefix() {
        publish(message());
        await().atMost(WAIT).untilAsserted(() -> assertThat(rowCount()).isEqualTo(4));

        List<Map<String, Object>> latest = RestClient.create()
                .get()
                .uri(
                        "http://localhost:%d/api/v1/debug/devices/{gw}/{device}/latest".formatted(port),
                        gatewayId,
                        "sensor-hall-02")
                .retrieve()
                .body(List.class);

        assertThat(metricValues(latest)).isEqualTo(EXPECTED.get("sensor-hall-02"));
        assertThat(latest).allSatisfy(m -> assertThat(m.get("deviceId")).isEqualTo("sensor-hall-02"));

        int unknown = RestClient.create()
                .get()
                .uri(
                        "http://localhost:%d/api/v1/debug/devices/{gw}/{device}/latest".formatted(port),
                        gatewayId,
                        "sensor-unknown")
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(unknown).isEqualTo(404);
    }

    private void publish(ValidatedMessageV1 message) {
        rabbitTemplate.convertAndSend(Exchanges.VALIDATED, RoutingKeys.TELEMETRY_V1, message);
    }

    /** Пример из контракта, переписанный на gatewayId этого теста, — так его отдал бы ingest. */
    private ValidatedMessageV1 message() {
        Envelope example = jsonMapper.readValue(EXAMPLE.toFile(), Envelope.class);
        Envelope envelope = new Envelope(
                example.schemaVersion(),
                Ids.newId(),
                gatewayId,
                example.type(),
                example.sentAt(),
                example.bootId(),
                example.seq(),
                example.payload(),
                example.signature());
        return new ValidatedMessageV1(envelope, Instant.parse("2026-11-05T10:15:31Z"), gatewayId, false);
    }

    private static Map<String, Double> metricValues(List<Map<String, Object>> rows) {
        return rows.stream()
                .collect(Collectors.toMap(
                        row -> (String) row.get("metric"), row -> ((Number) row.get("value")).doubleValue()));
    }

    private UUID device(String deviceExternalId) {
        return deviceResolver.resolve(gatewayId, deviceExternalId).orElseThrow().deviceId();
    }

    private int rowCount() {
        return deviceIds.stream()
                .mapToInt(id -> jdbc.queryForObject(
                        "SELECT count(*) FROM telemetry.measurements WHERE device_id = ?", Integer.class, id))
                .sum();
    }

    private boolean ownRows(List<Measurement> rows) {
        return rows != null
                && !rows.isEmpty()
                && deviceIds.contains(rows.getFirst().deviceId());
    }
}
