package dev.senso.core.devices.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.senso.core.devices.api.GatewayState;
import dev.senso.kernel.id.Ids;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GatewayTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

    @Test
    void currentLastWillAppliesEvenWhenItsTimestampPredatesHeartbeat() {
        Gateway gateway = gateway();
        gateway.applyStatus(GatewayState.ONLINE, "boot-current", NOW, "v1", NOW);
        gateway.applyStatus(GatewayState.ONLINE, "boot-current", NOW.plusSeconds(10), null, NOW.plusSeconds(11));
        assertThat(gateway.applyStatus(
                        GatewayState.OFFLINE, "boot-current", NOW.minusSeconds(1), null, NOW.plusSeconds(12)))
                .isTrue();
        assertThat(gateway.getState()).isEqualTo(GatewayState.OFFLINE);
        assertThat(gateway.getLastSeenAt()).isEqualTo(NOW.plusSeconds(11));
        assertThat(gateway.getFirmware()).isEqualTo("v1");
        assertThat(gateway.applyStatus(
                        GatewayState.OFFLINE, "boot-current", NOW.minusSeconds(1), null, NOW.plusSeconds(13)))
                .isFalse();
    }

    @Test
    void obsoleteBootCannotDisconnectReconnectedGateway() {
        Gateway gateway = gateway();
        gateway.applyStatus(GatewayState.ONLINE, "boot-old", NOW, null, NOW);
        gateway.applyStatus(GatewayState.ONLINE, "boot-current", NOW.plusSeconds(10), "v2", NOW.plusSeconds(11));
        assertThat(gateway.applyStatus(
                        GatewayState.OFFLINE, "boot-old", NOW.plusSeconds(12), null, NOW.plusSeconds(13)))
                .isFalse();
        assertThat(gateway.getState()).isEqualTo(GatewayState.ONLINE);
        assertThat(gateway.getBootId()).isEqualTo("boot-current");
    }

    @Test
    void offlineRequiresAnEstablishedOnlineBootEvenWhenDegradedArrivesFirst() {
        Gateway gateway = gateway();
        assertThat(gateway.applyStatus(GatewayState.OFFLINE, "boot-current", NOW, null, NOW))
                .isFalse();
        assertThat(gateway.applyStatus(GatewayState.DEGRADED, "boot-current", NOW, null, NOW))
                .isTrue();
        assertThat(gateway.applyStatus(GatewayState.OFFLINE, "boot-current", NOW, null, NOW))
                .isFalse();
        gateway.applyStatus(GatewayState.ONLINE, "boot-current", NOW, null, NOW);
        assertThat(gateway.applyStatus(
                        GatewayState.DEGRADED, "boot-current", NOW.plusSeconds(1), null, NOW.plusSeconds(2)))
                .isTrue();
        assertThat(gateway.getState()).isEqualTo(GatewayState.DEGRADED);
    }

    @Test
    void lateOnlineEstablishesBootWithoutOverwritingNewerDegradedState() {
        Gateway gateway = gateway();
        gateway.applyStatus(GatewayState.DEGRADED, "boot-current", NOW.plusSeconds(2), "v2", NOW.plusSeconds(3));
        gateway.applyStatus(GatewayState.ONLINE, "boot-current", NOW, "v1", NOW.plusSeconds(4));
        assertThat(gateway.getState()).isEqualTo(GatewayState.DEGRADED);
        assertThat(gateway.getStatusSentAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(gateway.getFirmware()).isEqualTo("v2");
        assertThat(gateway.applyStatus(
                        GatewayState.OFFLINE, "boot-current", NOW.minusSeconds(1), null, NOW.plusSeconds(5)))
                .isTrue();
    }

    @Test
    void lateOnlineStatusCannotReplaceNewerBoot() {
        Gateway gateway = gateway();
        gateway.applyStatus(GatewayState.ONLINE, "boot-current", NOW, null, NOW);
        assertThat(gateway.applyStatus(GatewayState.ONLINE, "boot-old", NOW.minusSeconds(1), "old", NOW.plusSeconds(1)))
                .isFalse();
        assertThat(gateway.getBootId()).isEqualTo("boot-current");
        assertThat(gateway.getFirmware()).isNull();
    }

    private static Gateway gateway() {
        return new Gateway(Ids.newId(), Ids.newId(), "gw-lab", "Lab", "hash", NOW);
    }
}
