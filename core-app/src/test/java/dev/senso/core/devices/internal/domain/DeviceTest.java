package dev.senso.core.devices.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.senso.core.devices.api.Connectivity;
import dev.senso.core.devices.api.DeviceCapability;
import dev.senso.core.devices.api.DeviceStatus;
import dev.senso.kernel.error.ConflictException;
import dev.senso.kernel.id.Ids;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeviceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    private static final UUID OWNER = Ids.newId();

    @Test
    void approvalAndEnablePreserveOriginalApprovalAudit() {
        Device device = device();
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.PENDING);
        device.changeStatus(DeviceStatus.ACTIVE, OWNER, NOW);
        device.changeStatus(DeviceStatus.DISABLED, OWNER, NOW.plusSeconds(1));
        device.changeStatus(DeviceStatus.ACTIVE, OWNER, NOW.plusSeconds(2));
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.ACTIVE);
        assertThat(device.getApprovedBy()).isEqualTo(OWNER);
        assertThat(device.getApprovedAt()).isEqualTo(NOW);
    }

    @Test
    void invalidTransitionsFailAndRepeatedCommandsAreIdempotent() {
        Device device = device();
        assertThatThrownBy(() -> device.changeStatus(DeviceStatus.DISABLED, OWNER, NOW))
                .isInstanceOf(ConflictException.class);
        device.changeStatus(DeviceStatus.REJECTED, OWNER, NOW);
        device.changeStatus(DeviceStatus.REJECTED, OWNER, NOW.plusSeconds(1));
        assertThat(device.getUpdatedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> device.changeStatus(DeviceStatus.ACTIVE, OWNER, NOW))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void onlyFreshDiscoveryReopensRejectedDevice() {
        Device device = device();
        device.discover("TEMPERATURE", null, Map.of("firmware", "v1"), NOW, NOW);
        device.changeStatus(DeviceStatus.REJECTED, OWNER, NOW.plusSeconds(1));
        device.discover("HUMIDITY", null, Map.of(), NOW.minusSeconds(1), NOW.plusSeconds(2));
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.REJECTED);
        assertThat(device.getDeviceType()).isEqualTo("TEMPERATURE");

        device.discover("TEMPERATURE", null, null, NOW.plusSeconds(3), NOW.plusSeconds(3));
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.PENDING);
        assertThat(device.getMetadata()).containsEntry("firmware", "v1");
        assertThat(device.getConnectivity()).isEqualTo(Connectivity.ONLINE);
    }

    @Test
    void delayedDiscoveryCannotUndoNewerInventoryOmission() {
        Device device = device();
        device.discover("TEMPERATURE", null, null, NOW.minusSeconds(5), NOW);
        device.disconnect(NOW.plusSeconds(10), NOW.plusSeconds(11));
        device.discover("TEMPERATURE", null, null, NOW.plusSeconds(5), NOW.plusSeconds(12));
        assertThat(device.getConnectivity()).isEqualTo(Connectivity.OFFLINE);
        assertThat(device.getLastSeenAt()).isEqualTo(NOW.minusSeconds(5));
        assertThat(device.getConnectivityObservedAt()).isEqualTo(NOW.plusSeconds(10));

        device.discover("TEMPERATURE", null, null, NOW.plusSeconds(15), NOW.plusSeconds(16));
        device.disconnect(NOW.plusSeconds(12), NOW.plusSeconds(17));
        assertThat(device.getConnectivity()).isEqualTo(Connectivity.ONLINE);
        assertThat(device.getLastSeenAt()).isEqualTo(NOW.plusSeconds(15));
    }

    @Test
    void metadataAndCapabilitiesAreDefensiveJsonSnapshotsIncludingNulls() {
        List<DeviceCapability> capabilities = new ArrayList<>(List.of(new DeviceCapability("temperature", null)));
        List<Object> nested = new ArrayList<>();
        nested.add(null);
        nested.add("<script>raw text</script>");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("values", nested);
        metadata.put("nullable", null);
        Device device = new Device(Ids.newId(), Ids.newId(), "sensor-1", "TEMPERATURE", capabilities, metadata, NOW);
        capabilities.clear();
        nested.clear();
        metadata.clear();

        assertThat(device.getCapabilities()).containsExactly(new DeviceCapability("temperature", null));
        assertThat(device.getMetadata()).containsEntry("nullable", null);
        assertThat((List<?>) device.getMetadata().get("values"))
                .isEqualTo(Arrays.asList(null, "<script>raw text</script>"));
        assertThatThrownBy(() -> device.getMetadata().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static Device device() {
        return new Device(Ids.newId(), Ids.newId(), "sensor-1", "TEMPERATURE", null, null, NOW);
    }
}
