package dev.senso.core.telemetry.internal.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.senso.core.shared.web.ProblemDetailsAdvice;
import dev.senso.core.telemetry.internal.persistence.Measurement;
import dev.senso.core.telemetry.internal.service.TelemetryQueryService;
import dev.senso.kernel.error.NotFoundException;
import dev.senso.kernel.error.ProblemTypes;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Форма ответа по контракту ({@code MeasurementDto}) и 404 Problem Details. Префикс /api/v1 — в {@code ApiPathPrefixIT}. */
class DebugTelemetryControllerTest {

    private static final String PATH = "/debug/devices/{gatewayId}/{deviceId}/latest";

    private final TelemetryQueryService queryService = mock(TelemetryQueryService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DebugTelemetryController(queryService))
            .setControllerAdvice(new ProblemDetailsAdvice())
            .build();

    @Test
    void returnsLatestValuePerMetric() throws Exception {
        UUID deviceId = UUID.randomUUID();
        when(queryService.latest("gw-emulator-01", "sensor-001"))
                .thenReturn(List.of(
                        new Measurement(Instant.parse("2026-11-05T10:15:29.500Z"), deviceId, "humidity", 41.0),
                        new Measurement(Instant.parse("2026-11-05T10:15:29.500Z"), deviceId, "temperature", 4.2)));

        mvc.perform(get(PATH, "gw-emulator-01", "sensor-001"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].deviceId").value("sensor-001"))
                .andExpect(jsonPath("$[0].metric").value("humidity"))
                .andExpect(jsonPath("$[0].value").value(41.0))
                .andExpect(jsonPath("$[1].metric").value("temperature"))
                .andExpect(jsonPath("$[1].value").value(4.2));
    }

    @Test
    void unknownDeviceIsNotFoundProblem() throws Exception {
        when(queryService.latest("gw-emulator-01", "sensor-404")).thenThrow(new NotFoundException("No measurements"));

        mvc.perform(get(PATH, "gw-emulator-01", "sensor-404"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ProblemTypes.NOT_FOUND));
    }
}
