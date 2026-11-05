package dev.senso.core.telemetry.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.senso.contracts.internal.ValidatedMessageV1;
import dev.senso.contracts.mqtt.Envelope;
import dev.senso.contracts.mqtt.MessageType;
import dev.senso.core.telemetry.internal.domain.DeviceResolver;
import dev.senso.core.telemetry.internal.domain.ResolvedDevice;
import dev.senso.core.telemetry.internal.persistence.Measurement;
import dev.senso.core.telemetry.internal.persistence.MeasurementWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class TelemetryWriteServiceTest {

    private static final String GATEWAY = "gw-emulator-01";
    private static final Instant TS = Instant.parse("2026-11-05T10:15:29.500Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-11-05T10:15:31Z");
    private static final UUID FRIDGE = UUID.fromString("0195f3c2-0000-7000-8000-000000000001");
    private static final UUID HALL = UUID.fromString("0195f3c2-0000-7000-8000-000000000002");

    private final DeviceResolver resolver = mock(DeviceResolver.class);
    private final MeasurementWriter writer = mock(MeasurementWriter.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final TelemetryWriteService service =
            new TelemetryWriteService(resolver, writer, JsonMapper.builder().build(), meters);

    @Test
    @SuppressWarnings("unchecked")
    void writesOneRowPerMetricOfActiveDevices() {
        when(resolver.resolve(GATEWAY, "sensor-fridge-01")).thenReturn(Optional.of(new ResolvedDevice(FRIDGE, true)));
        when(resolver.resolve(GATEWAY, "sensor-hall-02")).thenReturn(Optional.of(new ResolvedDevice(HALL, true)));
        when(writer.write(any(), eq(RECEIVED_AT))).thenReturn(OptionalInt.of(4));

        TelemetryWriteService.WriteResult result = service.write(message());

        ArgumentCaptor<List<Measurement>> rows = ArgumentCaptor.forClass(List.class);
        verify(writer).write(rows.capture(), eq(RECEIVED_AT));
        assertThat(rows.getValue())
                .containsExactlyInAnyOrder(
                        new Measurement(TS, FRIDGE, "temperature", 4.2),
                        new Measurement(TS, FRIDGE, "humidity", 41.0),
                        new Measurement(TS, HALL, "temperature", 21.7),
                        new Measurement(TS, HALL, "co2_ppm", 640.0));
        assertThat(result.rows()).isEqualTo(4);
        assertThat(meters.get(TelemetryWriteService.WRITTEN).counter().count()).isEqualTo(4);
        assertThat(meters.get(TelemetryWriteService.WRITE_TIMER).timer().count())
                .isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dropsValuesOfUnknownAndInactiveDevices() {
        when(resolver.resolve(GATEWAY, "sensor-fridge-01")).thenReturn(Optional.empty());
        when(resolver.resolve(GATEWAY, "sensor-hall-02")).thenReturn(Optional.of(new ResolvedDevice(HALL, false)));
        when(writer.write(any(), any())).thenReturn(OptionalInt.of(0));

        TelemetryWriteService.WriteResult result = service.write(message());

        ArgumentCaptor<List<Measurement>> rows = ArgumentCaptor.forClass(List.class);
        verify(writer).write(rows.capture(), any());
        assertThat(rows.getValue()).isEmpty();
        assertThat(result.droppedUnknown()).isEqualTo(2);
        assertThat(result.droppedInactive()).isEqualTo(2);
        assertThat(dropped(TelemetryWriteService.UNKNOWN_DEVICE)).isEqualTo(2);
        assertThat(dropped(TelemetryWriteService.INACTIVE_DEVICE)).isEqualTo(2);
    }

    @Test
    void duplicatesAreNotCountedAsWritten() {
        when(resolver.resolve(any(), any())).thenReturn(Optional.of(new ResolvedDevice(FRIDGE, true)));
        when(writer.write(any(), any())).thenReturn(OptionalInt.of(0));

        service.write(message());

        assertThat(meters.get(TelemetryWriteService.WRITTEN).counter().count()).isZero();
    }

    @Test
    void unknownInsertCountDoesNotFakeTheMetric() {
        when(resolver.resolve(any(), any())).thenReturn(Optional.of(new ResolvedDevice(FRIDGE, true)));
        when(writer.write(any(), any())).thenReturn(OptionalInt.empty());

        TelemetryWriteService.WriteResult result = service.write(message());

        assertThat(result.inserted()).isEmpty();
        assertThat(meters.get(TelemetryWriteService.WRITTEN).counter().count()).isZero();
    }

    private double dropped(String reason) {
        return meters.get(TelemetryWriteService.DROPPED)
                .tag("reason", reason)
                .counter()
                .count();
    }

    /** Как его отдаёт конвертер: payload — Map, а не record (ValidatedMessageV1.envelope.payload — Object). */
    private static ValidatedMessageV1 message() {
        Map<String, Object> payload = Map.of(
                "samples",
                List.of(
                        Map.of(
                                "deviceId",
                                "sensor-fridge-01",
                                "ts",
                                TS.toString(),
                                "metrics",
                                Map.of("temperature", 4.2, "humidity", 41.0)),
                        Map.of(
                                "deviceId",
                                "sensor-hall-02",
                                "ts",
                                TS.toString(),
                                "metrics",
                                Map.of("temperature", 21.7, "co2_ppm", 640))));
        Envelope envelope = new Envelope(
                1,
                UUID.fromString("0195f3c2-7d1e-7a4b-9c11-2b6f0e5a8d34"),
                GATEWAY,
                MessageType.TELEMETRY,
                Instant.parse("2026-11-05T10:15:30.120Z"),
                "0195f3b8-0a2b-7c3d-9e4f-a1b2c3d4e5f6",
                10423L,
                payload,
                null);
        return new ValidatedMessageV1(envelope, RECEIVED_AT, GATEWAY, false);
    }
}
