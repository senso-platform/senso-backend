package dev.senso.core.telemetry.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class StubDeviceResolverTest {

    private final StubDeviceResolver resolver = new StubDeviceResolver();

    @Test
    void sameGatewayAndDeviceAlwaysGiveSameActiveDevice() {
        ResolvedDevice first = resolver.resolve("gw-emulator-01", "sensor-001").orElseThrow();
        ResolvedDevice second =
                new StubDeviceResolver().resolve("gw-emulator-01", "sensor-001").orElseThrow();

        assertThat(first).isEqualTo(second);
        assertThat(first.active()).isTrue();
        assertThat(first.deviceId().version()).as("UUID v5").isEqualTo(5);
    }

    @Test
    void deviceIdDependsOnGatewayAndDevice() {
        UUID base = resolver.resolve("gw-a", "sensor-1").orElseThrow().deviceId();

        assertThat(resolver.resolve("gw-b", "sensor-1").orElseThrow().deviceId())
                .isNotEqualTo(base);
        assertThat(resolver.resolve("gw-a", "sensor-2").orElseThrow().deviceId())
                .isNotEqualTo(base);
    }
}
