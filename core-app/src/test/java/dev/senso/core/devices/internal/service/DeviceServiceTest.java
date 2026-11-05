package dev.senso.core.devices.internal.service;

import static dev.senso.core.support.OwnershipTestSupport.OWNER;
import static dev.senso.core.support.OwnershipTestSupport.STRANGER;
import static dev.senso.core.support.OwnershipTestSupport.assertHiddenFromStranger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.senso.core.devices.api.DeviceCapability;
import dev.senso.core.devices.api.DeviceStatus;
import dev.senso.core.devices.internal.domain.Device;
import dev.senso.core.devices.internal.domain.Gateway;
import dev.senso.core.devices.internal.mapping.DeviceMapper;
import dev.senso.core.devices.internal.repository.DeviceRepository;
import dev.senso.core.devices.internal.repository.GatewayRepository;
import dev.senso.kernel.error.ConflictException;
import dev.senso.kernel.error.ValidationException;
import dev.senso.kernel.page.Cursor;
import dev.senso.kernel.page.PageRequestParams;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class DeviceServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final GatewayRepository gateways = mock(GatewayRepository.class);
    private DeviceService service;
    private Gateway gateway;
    private Device device;

    @BeforeEach
    void setUp() {
        service = new DeviceService(
                devices, gateways, Mappers.getMapper(DeviceMapper.class), Clock.fixed(NOW, ZoneOffset.UTC));
        gateway = new Gateway(UUID.randomUUID(), OWNER, "gw-lab", "lab", "hash", NOW);
        device = device(gateway.getId());
    }

    @Test
    void ownerCanReadRenameApproveDisableAndEnableDevice() {
        when(devices.findById(device.getId())).thenReturn(Optional.of(device));
        when(gateways.findByIdAndOwnerId(gateway.getId(), OWNER)).thenReturn(Optional.of(gateway));
        when(gateways.lockByDeviceId(device.getId())).thenReturn(Optional.of(gateway));

        assertThat(service.find(OWNER, device.getId()).status()).isEqualTo(DeviceStatus.PENDING);
        assertThat(service.rename(OWNER, device.getId(), "<hall>").name()).isEqualTo("<hall>");
        var approved = service.approve(OWNER, device.getId());
        assertThat(approved.status()).isEqualTo(DeviceStatus.ACTIVE);
        assertThat(approved.approvedBy()).isEqualTo(OWNER);
        assertThat(approved.approvedAt()).isEqualTo(NOW);
        assertThat(service.approve(OWNER, device.getId()).approvedAt()).isEqualTo(NOW);
        assertThat(service.disable(OWNER, device.getId()).status()).isEqualTo(DeviceStatus.DISABLED);
        assertThat(service.enable(OWNER, device.getId()).status()).isEqualTo(DeviceStatus.ACTIVE);
    }

    @Test
    void ownerCanRejectPendingDeviceButCannotApproveItWithoutRediscovery() {
        when(devices.findById(device.getId())).thenReturn(Optional.of(device));
        when(gateways.lockByDeviceId(device.getId())).thenReturn(Optional.of(gateway));

        assertThat(service.reject(OWNER, device.getId()).status()).isEqualTo(DeviceStatus.REJECTED);
        assertThat(service.reject(OWNER, device.getId()).status()).isEqualTo(DeviceStatus.REJECTED);
        assertThatThrownBy(() -> service.approve(OWNER, device.getId())).isInstanceOf(ConflictException.class);
    }

    @Test
    void enableDoesNotApprovePendingDeviceAndApproveDoesNotEnableDisabledDevice() {
        when(devices.findById(device.getId())).thenReturn(Optional.of(device));
        when(gateways.lockByDeviceId(device.getId())).thenReturn(Optional.of(gateway));

        assertThatThrownBy(() -> service.enable(OWNER, device.getId())).isInstanceOf(ConflictException.class);
        service.approve(OWNER, device.getId());
        service.disable(OWNER, device.getId());
        assertThatThrownBy(() -> service.approve(OWNER, device.getId())).isInstanceOf(ConflictException.class);
    }

    @Test
    void strangerCannotReadOrMutateAnyDeviceOrListForeignGateway() {
        when(devices.findById(device.getId())).thenReturn(Optional.of(device));
        when(gateways.lockByDeviceId(device.getId())).thenReturn(Optional.of(gateway));

        assertHiddenFromStranger(() -> service.find(STRANGER, device.getId()));
        assertHiddenFromStranger(() -> service.approve(STRANGER, device.getId()));
        assertHiddenFromStranger(() -> service.reject(STRANGER, device.getId()));
        assertHiddenFromStranger(() -> service.disable(STRANGER, device.getId()));
        assertHiddenFromStranger(() -> service.enable(STRANGER, device.getId()));
        assertHiddenFromStranger(() -> service.rename(STRANGER, device.getId(), "stolen"));
        assertHiddenFromStranger(() -> service.list(STRANGER, gateway.getId(), PageRequestParams.of(null, null)));
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.PENDING);
        assertThat(device.getName()).isNull();
    }

    @Test
    void workerResolutionOnlyReturnsActiveDevicesAndUsesTheGatewayDevicePair() {
        when(gateways.findByExternalId("gw-lab")).thenReturn(Optional.of(gateway));
        when(devices.findByGatewayIdAndExternalId(gateway.getId(), device.getExternalId()))
                .thenReturn(Optional.of(device));

        assertThat(service.resolve("gw-lab", device.getExternalId())).isEmpty();
        device.changeStatus(DeviceStatus.ACTIVE, OWNER, NOW);
        assertThat(service.resolve("gw-lab", device.getExternalId()))
                .get()
                .extracting(view -> view.id())
                .isEqualTo(device.getId());
        device.changeStatus(DeviceStatus.DISABLED, OWNER, NOW);
        assertThat(service.resolve("gw-lab", device.getExternalId())).isEmpty();
        assertThat(service.resolve("gw-other", device.getExternalId())).isEmpty();
    }

    @Test
    void listRemainsOwnerScopedWithForeignCursorAndReadsOneExtraRow() {
        Device foreignCursorDevice = device(UUID.randomUUID());
        String cursor = new Cursor(foreignCursorDevice.getCreatedAt(), foreignCursorDevice.getId()).encode();
        Device extra = device(gateway.getId());
        when(devices.findPage(OWNER, null, NOW, foreignCursorDevice.getId(), 2)).thenReturn(List.of(device, extra));

        var page = service.list(OWNER, null, new PageRequestParams(1, cursor));

        assertThat(page.items()).extracting(view -> view.id()).containsExactly(device.getId());
        assertThat(Cursor.decode(page.nextCursor())).isEqualTo(new Cursor(NOW, device.getId()));
        verify(devices).findPage(OWNER, null, NOW, foreignCursorDevice.getId(), 2);
    }

    @Test
    void validatesDeviceNameInService() {
        when(devices.findById(device.getId())).thenReturn(Optional.of(device));
        when(gateways.lockByDeviceId(device.getId())).thenReturn(Optional.of(gateway));

        assertThatThrownBy(() -> service.rename(OWNER, device.getId(), " ")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.rename(OWNER, device.getId(), "x".repeat(129)))
                .isInstanceOf(ValidationException.class);
    }

    private static Device device(UUID gatewayId) {
        return new Device(
                UUID.randomUUID(),
                gatewayId,
                "sensor-1",
                "TEMPERATURE",
                List.of(new DeviceCapability("temperature", "CEL")),
                Map.of("firmware", "1.0"),
                NOW);
    }
}
