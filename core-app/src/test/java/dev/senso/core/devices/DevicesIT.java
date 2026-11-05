package dev.senso.core.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.senso.contracts.mqtt.payload.DeviceDiscoveredPayload;
import dev.senso.contracts.mqtt.payload.DeviceInventoryPayload;
import dev.senso.contracts.mqtt.payload.GatewayStatusPayload;
import dev.senso.core.IntegrationTestBase;
import dev.senso.core.devices.api.Connectivity;
import dev.senso.core.devices.api.DeviceQueries;
import dev.senso.core.devices.api.DeviceStatus;
import dev.senso.core.devices.api.DeviceView;
import dev.senso.core.devices.api.GatewayState;
import dev.senso.core.devices.api.GatewayView;
import dev.senso.core.devices.internal.service.DeviceDiscoveryService;
import dev.senso.core.devices.internal.service.DeviceService;
import dev.senso.core.devices.internal.service.GatewayService;
import dev.senso.core.devices.internal.service.RegisteredGateway;
import dev.senso.core.support.OwnershipTestSupport;
import dev.senso.kernel.error.ConflictException;
import dev.senso.kernel.error.NotFoundException;
import dev.senso.kernel.error.ValidationException;
import dev.senso.kernel.id.Ids;
import dev.senso.kernel.page.CursorPage;
import dev.senso.kernel.page.PageRequestParams;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("api")
@ApplicationModuleTest(
        mode = ApplicationModuleTest.BootstrapMode.DIRECT_DEPENDENCIES,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DevicesIT extends IntegrationTestBase {

    private static final Instant OBSERVED = Instant.parse("2026-11-05T10:00:00Z");

    @Autowired
    GatewayService gateways;

    @Autowired
    DeviceService devices;

    @Autowired
    DeviceQueries queries;

    @Autowired
    DeviceDiscoveryService discovery;

    @Autowired
    JdbcTemplate jdbc;

    private UUID owner;
    private UUID stranger;

    @BeforeEach
    void owners() {
        owner = createOwner();
        stranger = createOwner();
    }

    @Test
    void gatewayStoresOnlyBcryptHashAndChecksOwnerExistence() {
        RegisteredGateway created = gateways.create(owner, "<lab>");
        GatewayView gateway = created.gateway();
        assertThat(gateway.id().version()).isEqualTo(7);
        assertThat(gateway.externalId()).matches("[a-z0-9-]{3,64}");
        assertThat(gateway.name()).isEqualTo("<lab>");
        String hash = jdbc.queryForObject(
                "SELECT mqtt_password_hash FROM devices.gateways WHERE id = ?", String.class, gateway.id());
        assertThat(hash).startsWith("$2a$12$");
        assertThat(new BCryptPasswordEncoder().matches(created.mqttPassword(), hash))
                .isTrue();
        assertThat(created.toString()).doesNotContain(created.mqttPassword());
        assertThat(gateways.find(owner, gateway.id())).isEqualTo(gateway);
        assertThatThrownBy(() -> gateways.create(Ids.newId(), "missing owner")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void discoveryIsScopedToGatewayAndOnlyApprovedDevicesResolveForTelemetry() {
        GatewayView first = gateway();
        GatewayView second = gateway();
        UUID message = Ids.newId();
        discovery.discover(first.externalId(), message, OBSERVED, sensor("sensor-1"));
        discovery.discover(first.externalId(), message, OBSERVED, sensor("sensor-1"));
        discovery.discover(second.externalId(), message, OBSERVED, sensor("sensor-1"));
        DeviceView device = onlyDevice(first);
        assertThat(device.status()).isEqualTo(DeviceStatus.PENDING);
        assertThat(device.connectivity()).isEqualTo(Connectivity.ONLINE);
        assertThat(device.id()).isNotEqualTo(onlyDevice(second).id());
        assertThat(queries.resolve(first.externalId(), "sensor-1")).isEmpty();
        DeviceView approved = devices.approve(owner, device.id());
        assertThat(approved.approvedBy()).isEqualTo(owner);
        assertThat(approved.approvedAt()).isNotNull();
        assertThat(queries.resolve(first.externalId(), "sensor-1")).contains(approved);
        discovery.discover(first.externalId(), Ids.newId(), OBSERVED.plusSeconds(1), sensor("sensor-1"));
        assertThat(devices.find(owner, device.id()).status()).isEqualTo(DeviceStatus.ACTIVE);
        devices.disable(owner, device.id());
        assertThat(queries.resolve(first.externalId(), "sensor-1")).isEmpty();
        devices.enable(owner, device.id());
        assertThat(queries.resolve(first.externalId(), "sensor-1")).isPresent();
    }

    @Test
    void retransmissionCannotReopenRejectedDeviceButNewDiscoveryCan() {
        GatewayView gateway = gateway();
        UUID message = Ids.newId();
        discovery.discover(gateway.externalId(), message, OBSERVED, sensor("sensor-1"));
        DeviceView device = onlyDevice(gateway);
        devices.reject(owner, device.id());
        discovery.discover(gateway.externalId(), message, OBSERVED, sensor("sensor-1"));
        assertThat(devices.find(owner, device.id()).status()).isEqualTo(DeviceStatus.REJECTED);
        discovery.discover(gateway.externalId(), Ids.newId(), OBSERVED.plusSeconds(1), sensor("sensor-1"));
        assertThat(devices.find(owner, device.id()).status()).isEqualTo(DeviceStatus.PENDING);
    }

    @Test
    void foreignOwnerCannotReadOrChangeGatewaysAndDevices() {
        GatewayView gateway = gateway();
        discovery.discover(gateway.externalId(), Ids.newId(), OBSERVED, sensor("sensor-1"));
        UUID id = onlyDevice(gateway).id();
        OwnershipTestSupport.assertHiddenFromStranger(() -> gateways.find(stranger, gateway.id()));
        OwnershipTestSupport.assertHiddenFromStranger(() -> gateways.rename(stranger, gateway.id(), "stolen"));
        OwnershipTestSupport.assertHiddenFromStranger(() -> devices.find(stranger, id));
        OwnershipTestSupport.assertHiddenFromStranger(() -> devices.rename(stranger, id, "stolen"));
        OwnershipTestSupport.assertHiddenFromStranger(() -> devices.approve(stranger, id));
        OwnershipTestSupport.assertHiddenFromStranger(() -> devices.reject(stranger, id));
        OwnershipTestSupport.assertHiddenFromStranger(() -> devices.disable(stranger, id));
        OwnershipTestSupport.assertHiddenFromStranger(() -> devices.enable(stranger, id));
        OwnershipTestSupport.assertHiddenFromStranger(() -> devices.list(stranger, gateway.id(), page()));
        assertThat(devices.list(stranger, null, page()).items()).isEmpty();
        assertThat(gateways.list(stranger, page()).items()).isEmpty();
    }

    @Test
    void inventoryDisconnectsMissingDevicesAndStaleObservationsCannotReconnectThem() {
        GatewayView gateway = gateway();
        discovery.discover(gateway.externalId(), Ids.newId(), OBSERVED, sensor("sensor-1"));
        DeviceView initial = onlyDevice(gateway);
        discovery.inventory(
                gateway.externalId(), Ids.newId(), OBSERVED.plusSeconds(2), new DeviceInventoryPayload(List.of()));
        discovery.discover(gateway.externalId(), Ids.newId(), OBSERVED.plusSeconds(1), sensor("sensor-1"));
        discovery.discover(gateway.externalId(), Ids.newId(), OBSERVED.plusSeconds(1), sensor("late-unknown"));
        assertThat(devices.list(owner, gateway.id(), page()).items()).hasSize(1);
        DeviceView disconnected = devices.find(owner, initial.id());
        assertThat(disconnected.connectivity()).isEqualTo(Connectivity.OFFLINE);
        assertThat(disconnected.lastSeenAt()).isEqualTo(OBSERVED);
        DeviceInventoryPayload inventory =
                new DeviceInventoryPayload(List.of(new DeviceInventoryPayload.Entry("sensor-1", "TEMPERATURE", null)));
        discovery.inventory(gateway.externalId(), Ids.newId(), OBSERVED.plusSeconds(1), inventory);
        assertThat(devices.find(owner, initial.id()).connectivity()).isEqualTo(Connectivity.OFFLINE);
        UUID current = Ids.newId();
        discovery.inventory(gateway.externalId(), current, OBSERVED.plusSeconds(3), inventory);
        discovery.inventory(gateway.externalId(), current, OBSERVED.plusSeconds(3), inventory);
        DeviceView reconnected = devices.find(owner, initial.id());
        assertThat(reconnected.connectivity()).isEqualTo(Connectivity.ONLINE);
        assertThat(reconnected.metadata()).containsEntry("label", "<sensor>");
        assertThat(reconnected.status()).isEqualTo(DeviceStatus.PENDING);
    }

    @Test
    void lastWillIsAppliedOnlyToCurrentBootAndIsNotOrderedByItsStaticSentAt() {
        GatewayView gateway = gateway();
        discovery.updateGatewayStatus(gateway.externalId(), "boot-0001", OBSERVED, status("ONLINE"));
        discovery.updateGatewayStatus(gateway.externalId(), "boot-0002", OBSERVED.plusSeconds(3), status("DEGRADED"));
        discovery.updateGatewayStatus(gateway.externalId(), "boot-0002", OBSERVED.plusSeconds(2), status("ONLINE"));
        discovery.updateGatewayStatus(gateway.externalId(), "boot-0001", OBSERVED, status("OFFLINE"));
        assertThat(gateways.find(owner, gateway.id()).state()).isEqualTo(GatewayState.DEGRADED);
        discovery.updateGatewayStatus(gateway.externalId(), "boot-0002", OBSERVED.plusSeconds(1), status("OFFLINE"));
        discovery.updateGatewayStatus(gateway.externalId(), "boot-0002", OBSERVED.plusSeconds(1), status("OFFLINE"));
        assertThat(gateways.find(owner, gateway.id()).state()).isEqualTo(GatewayState.OFFLINE);
    }

    @Test
    void keysetPagesTraverseIdenticalTimestampsWithoutDuplicatesAndStayOwnerScoped() {
        GatewayView gateway = gateway();
        for (int index = 0; index < 5; index++) {
            discovery.discover(gateway.externalId(), Ids.newId(), OBSERVED, sensor("sensor-" + index));
        }
        jdbc.update(
                "UPDATE devices.devices SET created_at = ? WHERE gateway_id = ?",
                java.sql.Timestamp.from(OBSERVED),
                gateway.id());
        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        do {
            CursorPage<DeviceView> page = devices.list(owner, gateway.id(), new PageRequestParams(2, cursor));
            page.items().forEach(device -> seen.add(device.id()));
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(seen).hasSize(5).doesNotHaveDuplicates();
        String foreignCursor =
                devices.list(owner, null, new PageRequestParams(1, null)).nextCursor();
        assertThat(devices.list(stranger, null, new PageRequestParams(1, foreignCursor))
                        .items())
                .isEmpty();
        GatewayView second = gateway();
        assertThat(gateways.list(owner, new PageRequestParams(1, null)).nextCursor())
                .isNotNull();
        assertThat(second.id()).isNotEqualTo(gateway.id());
    }

    @Test
    void pendingLimitAndMalformedInventoryRollBackDeduplicationTogetherWithWrites() {
        GatewayView gateway = gateway();
        List<DeviceInventoryPayload.Entry> entries = new ArrayList<>();
        for (int index = 0; index < 51; index++) {
            entries.add(new DeviceInventoryPayload.Entry("sensor-" + index, "TEMPERATURE", null));
        }
        UUID failedMessage = Ids.newId();
        assertThatThrownBy(() -> discovery.inventory(
                        gateway.externalId(), failedMessage, OBSERVED, new DeviceInventoryPayload(entries)))
                .isInstanceOf(ConflictException.class);
        assertThat(devices.list(owner, gateway.id(), page()).items()).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM devices.processed_messages WHERE gateway_id = ?",
                        Long.class,
                        gateway.id()))
                .isZero();
        discovery.inventory(
                gateway.externalId(), Ids.newId(), OBSERVED, new DeviceInventoryPayload(entries.subList(0, 50)));
        assertThat(devices.list(owner, gateway.id(), page()).items()).hasSize(50);
        UUID overflowMessage = Ids.newId();
        assertThatThrownBy(() -> discovery.discover(
                        gateway.externalId(), overflowMessage, OBSERVED.plusSeconds(1), sensor("overflow")))
                .isInstanceOf(ConflictException.class);
        UUID existing =
                devices.list(owner, gateway.id(), page()).items().getFirst().id();
        devices.approve(owner, existing);
        discovery.discover(gateway.externalId(), overflowMessage, OBSERVED.plusSeconds(1), sensor("overflow"));
        assertThat(devices.list(owner, gateway.id(), new PageRequestParams(100, null))
                        .items())
                .hasSize(51);
        DeviceInventoryPayload.Entry entry = entries.getFirst();
        assertThatThrownBy(() -> discovery.inventory(
                        gateway.externalId(),
                        Ids.newId(),
                        OBSERVED.plusSeconds(1),
                        new DeviceInventoryPayload(List.of(entry, entry))))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void concurrentDuplicateDeliveriesCreateOneDevice() {
        GatewayView gateway = gateway();
        UUID message = Ids.newId();
        List<CompletableFuture<Void>> requests = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            requests.add(CompletableFuture.runAsync(
                    () -> discovery.discover(gateway.externalId(), message, OBSERVED, sensor("sensor-1"))));
        }
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();
        assertThat(devices.list(owner, gateway.id(), page()).items()).hasSize(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM devices.processed_messages WHERE gateway_id = ?",
                        Long.class,
                        gateway.id()))
                .isEqualTo(1L);
    }

    private UUID createOwner() {
        UUID id = Ids.newId();
        jdbc.update("""
                INSERT INTO identity.users (id, email, display_name, password_hash)
                VALUES (?, ?, 'devices test', 'unused-test-hash')
                """, id, id + "@devices.test");
        return id;
    }

    private GatewayView gateway() {
        return gateways.create(owner, "lab").gateway();
    }

    private DeviceView onlyDevice(GatewayView gateway) {
        List<DeviceView> items = devices.list(owner, gateway.id(), page()).items();
        assertThat(items).hasSize(1);
        return items.getFirst();
    }

    private PageRequestParams page() {
        return new PageRequestParams(50, null);
    }

    private DeviceDiscoveredPayload sensor(String id) {
        return new DeviceDiscoveredPayload(
                id,
                "TEMPERATURE",
                List.of(new DeviceDiscoveredPayload.Capability("temperature", "CEL")),
                Map.of("label", "<sensor>"));
    }

    private GatewayStatusPayload status(String state) {
        return new GatewayStatusPayload(state, "1.0", null, null, null);
    }
}
