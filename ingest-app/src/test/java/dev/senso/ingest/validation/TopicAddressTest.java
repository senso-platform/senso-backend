package dev.senso.ingest.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TopicAddressTest {

    @Test
    void parsesGatewayAndChannel() {
        TopicAddress address = TopicAddress.parse("senso.v1.gw.gw-emulator-01.telemetry");

        assertThat(address).isEqualTo(new TopicAddress("gw-emulator-01", "telemetry"));
        assertThat(address.knownChannel()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"telemetry", "events", "status"})
    void inboundChannelsAreKnown(String channel) {
        assertThat(TopicAddress.parse("senso.v1.gw.gw-1." + channel).knownChannel())
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ack", "cmd", "metrics"})
    void unknownOrOutboundChannelIsParsedButNotKnown(String channel) {
        TopicAddress address = TopicAddress.parse("senso.v1.gw.gw-1." + channel);

        assertThat(address.channel()).isEqualTo(channel);
        assertThat(address.knownChannel()).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "senso.v1.gw.gw-1.telemetry.extra", // лишний сегмент
                "senso.v1.gw..telemetry", // пустой gatewayId
                "senso.v1.gw.gw-1.", // пустой канал
                "senso.v1.gw.gw-1", // нет канала
                "senso.v2.gw.gw-1.telemetry", // чужая версия
                "senso.v1.gwx.gw-1.telemetry", // префикс совпадает только как строка
                "other.v1.gw.gw-1.telemetry"
            })
    void rejectsMalformedRoutingKey(String routingKey) {
        assertThatThrownBy(() -> TopicAddress.parse(routingKey))
                .isInstanceOfSatisfying(
                        MessageRejectionException.class,
                        e -> assertThat(e.reason()).isEqualTo(RejectReason.ROUTING_KEY));
    }
}
