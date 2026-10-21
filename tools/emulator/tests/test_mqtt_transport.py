from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

import paho.mqtt.client as mqtt
import pytest

from senso_emulator.config import EmulatorConfig
from senso_emulator.messages import OutboundMessage
from senso_emulator.mqtt_transport import MqttTransport, MqttTransportError


@dataclass
class FakeReasonCode:
    is_failure: bool = False
    text: str = "Normal disconnection"

    def __str__(self) -> str:
        return self.text


@dataclass
class FakeDisconnectFlags:
    is_disconnect_packet_from_server: bool = False


class FakeSocket:
    def __init__(self) -> None:
        self.shutdown_calls = 0
        self.close_calls = 0
        self.shutdown_error: OSError | None = None

    def shutdown(self, how: int) -> None:
        del how
        self.shutdown_calls += 1
        if self.shutdown_error is not None:
            raise self.shutdown_error

    def close(self) -> None:
        self.close_calls += 1


class FakeMessageInfo:
    def __init__(self, client: FakeClient) -> None:
        self.client = client
        self.rc = mqtt.MQTT_ERR_SUCCESS
        self.mid = 17
        self.published = True
        self.wait_error: Exception | None = None
        self.is_published_error: Exception | None = None
        self.on_wait: Callable[[], None] | None = None

    def wait_for_publish(self, timeout: float) -> None:
        self.client.calls.append(("wait_for_publish", timeout))
        if self.on_wait is not None:
            self.on_wait()
        if self.wait_error is not None:
            raise self.wait_error

    def is_published(self) -> bool:
        self.client.calls.append(("is_published",))
        if self.is_published_error is not None:
            raise self.is_published_error
        return self.published


class FakeClient:
    instances: list[FakeClient] = []
    setup: Callable[[FakeClient], None] | None = None

    def __init__(self, **kwargs: Any) -> None:
        self.kwargs = kwargs
        self.calls: list[tuple[Any, ...]] = []
        self.connect_timeout: float | None = None
        self.on_connect: Callable[..., None] | None = None
        self.on_disconnect: Callable[..., None] | None = None
        self.connect_result = mqtt.MQTT_ERR_SUCCESS
        self.loop_start_result = mqtt.MQTT_ERR_SUCCESS
        self.loop_stop_result = mqtt.MQTT_ERR_SUCCESS
        self.disconnect_result = mqtt.MQTT_ERR_SUCCESS
        self.connect_exception: Exception | None = None
        self.loop_start_exception: Exception | None = None
        self.disconnect_exception: Exception | None = None
        self.disconnect_action: Callable[[], None] | None = None
        self.disconnect_reason = FakeReasonCode()
        self.disconnect_flags = FakeDisconnectFlags()
        self.publish_exception: Exception | None = None
        self.connect_reason = FakeReasonCode()
        self.connect_callback = True
        self.disconnect_before_connack = False
        self.disconnect_callback = True
        self.socket_value = FakeSocket()
        self.message_info = FakeMessageInfo(self)
        type(self).instances.append(self)
        if type(self).setup is not None:
            type(self).setup(self)

    def username_pw_set(self, username: str, password: str) -> None:
        self.calls.append(("username_pw_set", username, password))

    def will_set(self, topic: str, *, payload: bytes, qos: int, retain: bool) -> None:
        self.calls.append(("will_set", topic, payload, qos, retain))

    def connect(self, host: str, port: int, *, keepalive: int) -> mqtt.MQTTErrorCode:
        self.calls.append(("connect", host, port, keepalive))
        if self.connect_exception is not None:
            raise self.connect_exception
        return self.connect_result

    def loop_start(self) -> mqtt.MQTTErrorCode:
        self.calls.append(("loop_start",))
        if self.loop_start_exception is not None:
            raise self.loop_start_exception
        if self.loop_start_result == mqtt.MQTT_ERR_SUCCESS:
            if self.disconnect_before_connack:
                self.fire_disconnect("Unspecified error")
            elif self.connect_callback and self.on_connect is not None:
                self.on_connect(self, None, None, self.connect_reason, None)
        return self.loop_start_result

    def publish(self, topic: str, *, payload: bytes, qos: int, retain: bool) -> FakeMessageInfo:
        self.calls.append(("publish", topic, payload, qos, retain))
        if self.publish_exception is not None:
            raise self.publish_exception
        return self.message_info

    def disconnect(self) -> mqtt.MQTTErrorCode:
        self.calls.append(("disconnect",))
        if self.disconnect_exception is not None:
            raise self.disconnect_exception
        if self.disconnect_action is not None:
            self.disconnect_action()
        if self.disconnect_callback and self.on_disconnect is not None:
            self.on_disconnect(
                self,
                None,
                self.disconnect_flags,
                self.disconnect_reason,
                None,
            )
        return self.disconnect_result

    def loop_stop(self) -> mqtt.MQTTErrorCode:
        self.calls.append(("loop_stop",))
        return self.loop_stop_result

    def socket(self) -> FakeSocket:
        self.calls.append(("socket",))
        return self.socket_value

    def fire_disconnect(
        self,
        reason: str = "Connection lost",
        *,
        is_failure: bool = True,
        from_server: bool = False,
    ) -> None:
        if self.on_disconnect is not None:
            self.on_disconnect(
                self,
                None,
                FakeDisconnectFlags(from_server),
                FakeReasonCode(is_failure, reason),
                None,
            )


@pytest.fixture(autouse=True)
def fake_paho_client(monkeypatch: pytest.MonkeyPatch) -> None:
    FakeClient.instances = []
    FakeClient.setup = None
    monkeypatch.setattr("senso_emulator.mqtt_transport.mqtt.Client", FakeClient)


@pytest.fixture
def config() -> EmulatorConfig:
    return EmulatorConfig(
        host="broker.test",
        port=1883,
        gateway_id="gw-emulator-01",
        password="secret-value",
        keepalive=30,
        timeout=0.01,
    )


@pytest.fixture
def message() -> OutboundMessage:
    return OutboundMessage(
        topic="senso/v1/gw/gw-emulator-01/status",
        document={},
        payload=b'{"safe":"payload"}',
        retain=True,
    )


def create_transport(
    config: EmulatorConfig,
    message: OutboundMessage,
) -> tuple[MqttTransport, FakeClient]:
    transport = MqttTransport(config, message)
    return transport, FakeClient.instances[-1]


def test_configures_mqtt_311_no_reconnect_will_and_connect_timeout(
    config: EmulatorConfig, message: OutboundMessage
) -> None:
    transport, client = create_transport(config, message)

    assert client.kwargs["protocol"] == mqtt.MQTTv311
    assert client.kwargs["reconnect_on_failure"] is False
    assert client.connect_timeout == config.timeout
    assert client.calls[:2] == [
        ("username_pw_set", config.gateway_id, config.password),
        ("will_set", message.topic, message.payload, 1, True),
    ]

    transport.abort()


def test_happy_path_waits_for_puback_then_disconnects(
    config: EmulatorConfig, message: OutboundMessage
) -> None:
    transport, client = create_transport(config, message)

    transport.connect()
    assert transport.publish(message) == 17
    transport.disconnect_gracefully()

    names = [call[0] for call in client.calls]
    assert names.index("wait_for_publish") < names.index("is_published") < names.index("disconnect")
    assert names[-1] == "loop_stop"
    assert client.socket_value.close_calls == 0


def test_connection_loss_before_connack_aborts_without_disconnect(
    config: EmulatorConfig, message: OutboundMessage
) -> None:
    FakeClient.setup = lambda client: setattr(client, "disconnect_before_connack", True)
    transport, client = create_transport(config, message)

    with pytest.raises(MqttTransportError, match="lost"):
        transport.connect()

    assert ("disconnect",) not in client.calls
    assert client.socket_value.close_calls == 1


def test_connack_timeout_aborts_without_disconnect(
    config: EmulatorConfig, message: OutboundMessage
) -> None:
    FakeClient.setup = lambda client: setattr(client, "connect_callback", False)
    transport, client = create_transport(config, message)

    with pytest.raises(MqttTransportError, match="CONNACK"):
        transport.connect()

    assert ("disconnect",) not in client.calls
    assert client.socket_value.close_calls == 1


def test_rejected_credentials_are_reported_without_secret(
    config: EmulatorConfig, message: OutboundMessage
) -> None:
    FakeClient.setup = lambda client: setattr(
        client,
        "connect_reason",
        FakeReasonCode(True, "Not authorized"),
    )
    transport, client = create_transport(config, message)

    with pytest.raises(MqttTransportError, match="rejected.*Not authorized") as caught:
        transport.connect()

    assert config.password not in str(caught.value)
    assert ("disconnect",) not in client.calls


def test_connection_loss_during_puback_is_reported_and_prevents_more_publishes(
    config: EmulatorConfig, message: OutboundMessage
) -> None:
    transport, client = create_transport(config, message)
    transport.connect()
    client.message_info.on_wait = client.fire_disconnect

    with pytest.raises(MqttTransportError, match="lost"):
        transport.publish(message)
    publish_count = sum(call[0] == "publish" for call in client.calls)
    with pytest.raises(MqttTransportError, match="lost"):
        transport.publish(message)

    assert sum(call[0] == "publish" for call in client.calls) == publish_count
    transport.abort()
    assert ("disconnect",) not in client.calls


@pytest.mark.parametrize("error", [RuntimeError("not connected"), ValueError("bad qos")])
def test_publish_errors_are_normalized_with_cause(
    config: EmulatorConfig, message: OutboundMessage, error: Exception
) -> None:
    transport, client = create_transport(config, message)
    transport.connect()
    client.publish_exception = error

    with pytest.raises(MqttTransportError, match="could not be started") as caught:
        transport.publish(message)

    assert caught.value.__cause__ is error
    assert config.password not in str(caught.value)
    assert message.payload.decode() not in str(caught.value)
    transport.abort()


@pytest.mark.parametrize("error", [RuntimeError("wait failed"), ValueError("queue full")])
def test_wait_for_publish_errors_are_normalized_with_cause(
    config: EmulatorConfig, message: OutboundMessage, error: Exception
) -> None:
    transport, client = create_transport(config, message)
    transport.connect()
    client.message_info.wait_error = error

    with pytest.raises(MqttTransportError, match="confirmation") as caught:
        transport.publish(message)

    assert caught.value.__cause__ is error
    assert config.password not in str(caught.value)
    assert message.payload.decode() not in str(caught.value)
    transport.abort()


def test_is_published_error_is_normalized_with_cause(
    config: EmulatorConfig, message: OutboundMessage
) -> None:
    transport, client = create_transport(config, message)
    transport.connect()
    error = ValueError("bad state")
    client.message_info.is_published_error = error

    with pytest.raises(MqttTransportError, match="confirmation") as caught:
        transport.publish(message)

    assert caught.value.__cause__ is error
    transport.abort()


def test_loop_start_error_is_normalized_and_aborted(
    config: EmulatorConfig, message: OutboundMessage
) -> None:
    FakeClient.setup = lambda client: setattr(client, "loop_start_exception", RuntimeError("boom"))
    transport, client = create_transport(config, message)

    with pytest.raises(MqttTransportError, match="cannot connect") as caught:
        transport.connect()

    assert isinstance(caught.value.__cause__, RuntimeError)
    assert ("disconnect",) not in client.calls
    assert client.socket_value.close_calls == 1


def test_loop_start_return_code_is_checked(
    config: EmulatorConfig, message: OutboundMessage
) -> None:
    FakeClient.setup = lambda client: setattr(client, "loop_start_result", mqtt.MQTT_ERR_INVAL)
    transport, client = create_transport(config, message)

    with pytest.raises(MqttTransportError, match="network loop failed"):
        transport.connect()

    assert ("disconnect",) not in client.calls
    assert client.socket_value.close_calls == 1


def test_disconnect_error_aborts_without_masking_original(
    config: EmulatorConfig, message: OutboundMessage
) -> None:
    error = RuntimeError("disconnect failed")
    FakeClient.setup = lambda client: setattr(client, "disconnect_exception", error)
    transport, client = create_transport(config, message)
    transport.connect()

    with pytest.raises(MqttTransportError, match="disconnect failed") as caught:
        transport.disconnect_gracefully()

    assert caught.value.__cause__ is error
    assert client.socket_value.close_calls == 1


def test_disconnect_return_code_is_checked_and_connection_is_aborted(
    config: EmulatorConfig, message: OutboundMessage
) -> None:
    FakeClient.setup = lambda client: setattr(client, "disconnect_result", mqtt.MQTT_ERR_INVAL)
    transport, client = create_transport(config, message)
    transport.connect()

    with pytest.raises(MqttTransportError, match="disconnect failed with code"):
        transport.disconnect_gracefully()

    assert client.socket_value.close_calls == 1


def test_graceful_disconnect_accepts_already_stopped_network_loop(
    config: EmulatorConfig, message: OutboundMessage
) -> None:
    transport, client = create_transport(config, message)
    transport.connect()
    client.loop_stop_result = mqtt.MQTT_ERR_INVAL

    transport.disconnect_gracefully()

    assert client.socket_value.close_calls == 0


def test_graceful_disconnect_rejects_other_loop_stop_errors(
    config: EmulatorConfig, message: OutboundMessage
) -> None:
    transport, client = create_transport(config, message)
    transport.connect()
    client.loop_stop_result = mqtt.MQTT_ERR_NOMEM

    with pytest.raises(MqttTransportError, match="loop stop failed"):
        transport.disconnect_gracefully()

    assert client.socket_value.close_calls == 1


def test_disconnect_no_connection_without_successful_callback_is_an_error(
    config: EmulatorConfig, message: OutboundMessage
) -> None:
    transport, client = create_transport(config, message)
    transport.connect()
    client.disconnect_callback = False
    client.disconnect_result = mqtt.MQTT_ERR_NO_CONN

    with pytest.raises(MqttTransportError, match="disconnect failed with code 4"):
        transport.disconnect_gracefully()

    assert client.socket_value.close_calls == 1


def test_disconnect_no_connection_after_successful_callback_is_graceful(
    config: EmulatorConfig, message: OutboundMessage
) -> None:
    transport, client = create_transport(config, message)
    transport.connect()
    client.disconnect_result = mqtt.MQTT_ERR_NO_CONN
    client.loop_stop_result = mqtt.MQTT_ERR_INVAL

    transport.disconnect_gracefully()

    assert client.calls.count(("disconnect",)) == 1
    assert client.calls.count(("loop_stop",)) == 1
    assert client.socket_value.close_calls == 0


def test_connection_loss_between_state_check_and_disconnect_is_preserved(
    config: EmulatorConfig, message: OutboundMessage
) -> None:
    transport, client = create_transport(config, message)
    transport.connect()
    client.disconnect_callback = False
    client.disconnect_result = mqtt.MQTT_ERR_NO_CONN
    client.disconnect_action = lambda: client.fire_disconnect("Connection reset")

    with pytest.raises(
        MqttTransportError,
        match="disconnect failed with code 4.*Connection reset",
    ):
        transport.disconnect_gracefully()

    with pytest.raises(MqttTransportError, match="Connection reset"):
        transport.publish(message)
    assert client.socket_value.close_calls == 1


def test_failure_callback_during_graceful_request_is_not_success(
    config: EmulatorConfig, message: OutboundMessage
) -> None:
    transport, client = create_transport(config, message)
    transport.connect()
    client.disconnect_reason = FakeReasonCode(True, "Connection reset")

    with pytest.raises(MqttTransportError, match="Connection reset"):
        transport.disconnect_gracefully()

    assert client.socket_value.close_calls == 1


def test_server_disconnect_is_not_graceful_even_with_success_reason(
    config: EmulatorConfig, message: OutboundMessage
) -> None:
    transport, client = create_transport(config, message)
    transport.connect()
    client.disconnect_flags = FakeDisconnectFlags(is_disconnect_packet_from_server=True)

    with pytest.raises(MqttTransportError, match="connection was lost"):
        transport.disconnect_gracefully()

    assert client.socket_value.close_calls == 1


def test_cleanup_error_does_not_mask_disconnect_error(
    config: EmulatorConfig,
    message: OutboundMessage,
    caplog: pytest.LogCaptureFixture,
) -> None:
    error = RuntimeError("primary disconnect failure")
    transport, client = create_transport(config, message)
    transport.connect()
    client.disconnect_exception = error
    client.loop_stop_result = mqtt.MQTT_ERR_INVAL
    client.socket_value.shutdown_error = OSError("shutdown failed")

    with pytest.raises(MqttTransportError, match="disconnect failed") as caught:
        transport.disconnect_gracefully()

    assert caught.value.__cause__ is error
    assert "loop stop failed with code 3" in caplog.text


def test_close_is_idempotent_after_graceful_disconnect(
    config: EmulatorConfig, message: OutboundMessage
) -> None:
    transport, client = create_transport(config, message)
    transport.connect()
    transport.disconnect_gracefully()

    transport.close()
    transport.close()

    assert client.calls.count(("disconnect",)) == 1
    assert client.calls.count(("loop_stop",)) == 1
    assert client.socket_value.close_calls == 0


def test_abort_checks_loop_stop_and_is_idempotent(
    config: EmulatorConfig, message: OutboundMessage, caplog: pytest.LogCaptureFixture
) -> None:
    transport, client = create_transport(config, message)
    transport.connect()
    client.loop_stop_result = mqtt.MQTT_ERR_INVAL
    client.socket_value.shutdown_error = OSError("shutdown failed")

    cleanup_error = transport.abort()
    transport.close()

    assert cleanup_error is not None
    assert "loop stop failed with code 3" in str(cleanup_error)
    assert client.calls.count(("loop_stop",)) == 1
    assert client.socket_value.close_calls == 1
    assert "loop stop failed" in caplog.text
    assert ("disconnect",) not in client.calls


def test_abort_accepts_already_stopped_loop_after_socket_was_closed(
    config: EmulatorConfig, message: OutboundMessage
) -> None:
    transport, client = create_transport(config, message)
    transport.connect()
    client.loop_stop_result = mqtt.MQTT_ERR_INVAL

    cleanup_error = transport.abort()

    assert cleanup_error is None
    assert client.socket_value.shutdown_calls == 1
    assert client.socket_value.close_calls == 1
    assert client.calls.count(("loop_stop",)) == 1
    assert ("disconnect",) not in client.calls
