package dev.senso.core.devices.internal.service;

import static dev.senso.core.support.OwnershipTestSupport.OWNER;
import static dev.senso.core.support.OwnershipTestSupport.STRANGER;
import static dev.senso.core.support.OwnershipTestSupport.assertHiddenFromStranger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.senso.core.devices.internal.domain.Gateway;
import dev.senso.core.devices.internal.mapping.DeviceMapper;
import dev.senso.core.devices.internal.repository.GatewayRepository;
import dev.senso.core.identity.api.IdentityApi;
import dev.senso.kernel.error.NotFoundException;
import dev.senso.kernel.error.ValidationException;
import dev.senso.kernel.page.Cursor;
import dev.senso.kernel.page.PageRequestParams;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class GatewayServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private final GatewayRepository gateways = mock(GatewayRepository.class);
    private final IdentityApi identity = mock(IdentityApi.class);
    private GatewayService service;

    @BeforeEach
    void setUp() {
        service = new GatewayService(
                gateways, identity, Mappers.getMapper(DeviceMapper.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsGatewayForExistingOwnerAndStoresOnlyBcryptPassword() {
        when(identity.existsById(OWNER)).thenReturn(true);
        when(gateways.save(any())).thenAnswer(call -> call.getArgument(0));

        RegisteredGateway result = service.create(OWNER, "<lab>");

        ArgumentCaptor<Gateway> saved = ArgumentCaptor.forClass(Gateway.class);
        verify(gateways).save(saved.capture());
        Gateway gateway = saved.getValue();
        assertThat(gateway.getId().version()).isEqualTo(7);
        assertThat(gateway.getOwnerId()).isEqualTo(OWNER);
        assertThat(gateway.getExternalId()).matches("[a-z0-9-]{3,64}");
        assertThat(gateway.getName()).isEqualTo("<lab>");
        assertThat(gateway.getCreatedAt()).isEqualTo(NOW);
        assertThat(result.mqttPassword()).hasSize(43);
        assertThat(gateway.getMqttPasswordHash()).startsWith("$2a$12$");
        assertThat(new BCryptPasswordEncoder().matches(result.mqttPassword(), gateway.getMqttPasswordHash()))
                .isTrue();
        assertThat(result.toString()).doesNotContain(result.mqttPassword(), gateway.getMqttPasswordHash());
        assertThat(result.gateway().id()).isEqualTo(gateway.getId());
    }

    @Test
    void rejectsMissingOwnerAndInvalidNames() {
        assertThatThrownBy(() -> service.create(OWNER, "lab")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.create(OWNER, " ")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.create(OWNER, "x".repeat(129))).isInstanceOf(ValidationException.class);
    }

    @Test
    void strangerCannotGetOrRenameGateway() {
        Gateway gateway = gateway(OWNER);
        when(gateways.lockById(gateway.getId())).thenReturn(Optional.of(gateway));

        assertHiddenFromStranger(() -> service.find(STRANGER, gateway.getId()));
        assertHiddenFromStranger(() -> service.rename(STRANGER, gateway.getId(), "changed"));
        assertThat(gateway.getName()).isEqualTo("lab");
    }

    @Test
    void findsAndRenamesGatewayForItsOwner() {
        Gateway gateway = gateway(OWNER);
        when(gateways.findByIdAndOwnerId(gateway.getId(), OWNER)).thenReturn(Optional.of(gateway));
        when(gateways.lockById(gateway.getId())).thenReturn(Optional.of(gateway));

        assertThat(service.find(OWNER, gateway.getId()).ownerId()).isEqualTo(OWNER);
        assertThat(service.rename(OWNER, gateway.getId(), "updated").name()).isEqualTo("updated");
    }

    @Test
    void listsUseOwnerScopeEvenWhenCursorComesFromAnotherUser() {
        Gateway foreign = gateway(STRANGER);
        String cursor = new Cursor(foreign.getCreatedAt(), foreign.getId()).encode();
        Gateway own = gateway(OWNER);
        Gateway extra = gateway(OWNER);
        when(gateways.findPage(OWNER, NOW, foreign.getId(), 2)).thenReturn(List.of(own, extra));

        var result = service.list(OWNER, new PageRequestParams(1, cursor));

        assertThat(result.items()).extracting(item -> item.ownerId()).containsExactly(OWNER);
        assertThat(Cursor.decode(result.nextCursor())).isEqualTo(new Cursor(own.getCreatedAt(), own.getId()));
        verify(gateways).findPage(OWNER, NOW, foreign.getId(), 2);
    }

    private static Gateway gateway(UUID owner) {
        return new Gateway(UUID.randomUUID(), owner, "gw-lab", "lab", "hash", NOW);
    }
}
