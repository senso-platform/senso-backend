from __future__ import annotations

from datetime import UTC, datetime
from typing import ClassVar
from uuid import UUID

import pytest
from jsonschema import Draft202012Validator, FormatChecker

from senso_emulator.config import EmulatorConfig
from senso_emulator.messages import OutboundMessage
from senso_emulator.mqtt_transport import MqttTransportError
from senso_emulator.scenarios import (
    run_anomaly,
    run_discover,
    run_duplicate,
    run_normal,
    run_offline,
)


class FakeTransport:
    instances: ClassVar[list[FakeTransport]] = []
    fail_message_type: ClassVar[str | None] = None
    fail_gateway_state: ClassVar[str | None] = None
    lose_after_message_type: ClassVar[str | None] = None
    abort_error: ClassVar[MqttTransportError | None] = None

    def __init__(self, config: EmulatorConfig, last_will: OutboundMessage) -> None:
        self.config = config
        self.last_will = last_will
        self.calls: list[tuple[str, str | None]] = []
        self.messages: list[OutboundMessage] = []
        self.connection_lost = False
        type(self).instances.append(self)

    def connect(self) -> None:
        self.calls.append(("connect", None))

    def publish(self, message: OutboundMessage) -> int:
        if self.connection_lost:
            raise MqttTransportError("MQTT connection was lost")
        message_type = str(message.document["type"])
        self.calls.append(("publish", message_type))
        self.messages.append(message)
        gateway_state = message.document["payload"].get("state")
        if message_type == type(self).fail_message_type or (
            message_type == "GATEWAY_STATUS" and gateway_state == type(self).fail_gateway_state
        ):
            raise MqttTransportError("MQTT publish confirmation failed")
        if message_type == type(self).lose_after_message_type:
            self.connection_lost = True
        return len(self.messages)

    def disconnect_gracefully(self) -> None:
        self.calls.append(("disconnect", None))

    def abort(self) -> MqttTransportError | None:
        self.calls.append(("abort", None))
        return type(self).abort_error


@pytest.fixture(autouse=True)
def reset_fake_transport() -> None:
    FakeTransport.instances = []
    FakeTransport.fail_message_type = None
    FakeTransport.fail_gateway_state = None
    FakeTransport.lose_after_message_type = None
    FakeTransport.abort_error = None


@pytest.fixture
def once_config() -> EmulatorConfig:
    return EmulatorConfig(
        "broker.test",
        1883,
        "gw-emulator-01",
        "secret-value",
        device_count=2,
        discovered_device_count=1,
        seed=42,
        once=True,
    )


def test_normal_once_sequence_identity_topics_and_retain(once_config: EmulatorConfig) -> None:
    result = run_normal(
        once_config,
        transport_factory=FakeTransport,
        clock=_fixed_clock,
        sleeper=_unexpected_sleep,
    )
    transport = FakeTransport.instances[-1]

    assert transport.calls == [
        ("connect", None),
        ("publish", "GATEWAY_STATUS"),
        ("publish", "DEVICE_INVENTORY"),
        ("publish", "DEVICE_DISCOVERED"),
        ("publish", "TELEMETRY"),
        ("publish", "GATEWAY_STATUS"),
        ("disconnect", None),
    ]
    assert [message.document["seq"] for message in result.messages] == list(range(5))
    assert all(message.document["bootId"] == result.boot_id for message in result.messages)
    assert len({message.document["messageId"] for message in result.messages}) == 5
    assert all(UUID(message.document["messageId"]).version == 7 for message in result.messages)
    assert result.online.retain is True
    assert result.offline.retain is True
    assert all(not message.retain for message in result.messages[1:-1])
    assert result.inventory.topic.endswith("/events")
    assert result.discovered[0].topic.endswith("/events")
    assert result.telemetry[0].topic.endswith("/telemetry")

    inventory_ids = {
        device["deviceId"] for device in result.inventory.document["payload"]["devices"]
    }
    discovered_ids = {message.document["payload"]["deviceId"] for message in result.discovered}
    assert inventory_ids.isdisjoint(discovered_ids)
    assert {
        sample["deviceId"] for sample in result.telemetry[0].document["payload"]["samples"]
    } == inventory_ids | discovered_ids
    assert transport.last_will.document["bootId"] == result.boot_id
    assert "seq" not in transport.last_will.document


def test_offline_publishes_online_then_aborts_without_disconnect(
    once_config: EmulatorConfig,
    envelope_schema: dict,
    gateway_status_schema: dict,
) -> None:
    result = run_offline(
        once_config,
        transport_factory=FakeTransport,
        clock=_fixed_clock,
    )
    transport = FakeTransport.instances[-1]

    assert transport.calls == [
        ("connect", None),
        ("publish", "GATEWAY_STATUS"),
        ("abort", None),
    ]
    assert result.online.document["payload"]["state"] == "ONLINE"
    assert result.last_will.document["payload"]["state"] == "OFFLINE"
    assert result.online.document["bootId"] == result.boot_id
    assert result.last_will.document["bootId"] == result.boot_id
    assert result.online.document["messageId"] != result.last_will.document["messageId"]
    assert UUID(result.online.document["messageId"]).version == 7
    assert UUID(result.last_will.document["messageId"]).version == 7
    assert result.online.document["seq"] == 0
    assert "seq" not in result.last_will.document
    assert result.online.topic == "senso/v1/gw/gw-emulator-01/status"
    assert result.last_will.topic == result.online.topic
    assert result.online.qos == result.last_will.qos == 1
    assert result.online.retain is result.last_will.retain is True

    envelope_validator = Draft202012Validator(envelope_schema, format_checker=FormatChecker())
    payload_validator = Draft202012Validator(
        gateway_status_schema,
        format_checker=FormatChecker(),
    )
    for message in (result.online, result.last_will):
        assert list(envelope_validator.iter_errors(message.document)) == []
        assert list(payload_validator.iter_errors(message.document["payload"])) == []


def test_offline_uses_a_new_boot_id_for_each_session(once_config: EmulatorConfig) -> None:
    first = run_offline(once_config, transport_factory=FakeTransport, clock=_fixed_clock)
    second = run_offline(once_config, transport_factory=FakeTransport, clock=_fixed_clock)

    assert first.boot_id != second.boot_id


def test_offline_online_publish_failure_aborts_without_disconnect(
    once_config: EmulatorConfig,
) -> None:
    FakeTransport.fail_gateway_state = "ONLINE"

    with pytest.raises(MqttTransportError, match="confirmation"):
        run_offline(once_config, transport_factory=FakeTransport, clock=_fixed_clock)

    transport = FakeTransport.instances[-1]
    assert transport.calls[-1] == ("abort", None)
    assert ("disconnect", None) not in transport.calls


def test_offline_reports_abort_cleanup_failure(once_config: EmulatorConfig) -> None:
    FakeTransport.abort_error = MqttTransportError("controlled abort failed")

    with pytest.raises(MqttTransportError, match="controlled abort failed"):
        run_offline(once_config, transport_factory=FakeTransport, clock=_fixed_clock)

    transport = FakeTransport.instances[-1]
    assert transport.calls.count(("abort", None)) == 1
    assert ("disconnect", None) not in transport.calls


def test_anomaly_publishes_gateway_event_and_disconnects(
    once_config: EmulatorConfig,
    envelope_schema: dict,
    security_event_schema: dict,
) -> None:
    result = run_anomaly(once_config, transport_factory=FakeTransport, clock=_fixed_clock)
    transport = FakeTransport.instances[-1]

    assert transport.calls == [
        ("connect", None),
        ("publish", "GATEWAY_STATUS"),
        ("publish", "SECURITY_EVENT"),
        ("publish", "GATEWAY_STATUS"),
        ("disconnect", None),
    ]
    assert [message.document["seq"] for message in result.messages] == [0, 1, 2]
    assert all(message.document["bootId"] == result.boot_id for message in result.messages)
    assert result.event.document["payload"]["severity"] == "WARNING"
    assert "deviceId" not in result.event.document["payload"]
    assert result.event.topic.endswith("/events")
    assert result.event.qos == 1
    assert result.event.retain is False
    assert UUID(result.event.document["messageId"]).version == 7
    envelope_validator = Draft202012Validator(envelope_schema, format_checker=FormatChecker())
    payload_validator = Draft202012Validator(
        security_event_schema,
        format_checker=FormatChecker(),
    )
    assert list(envelope_validator.iter_errors(result.event.document)) == []
    assert list(payload_validator.iter_errors(result.event.document["payload"])) == []


def test_discover_inventory_is_snapshot_before_unique_delta(once_config: EmulatorConfig) -> None:
    result = run_discover(once_config, transport_factory=FakeTransport, clock=_fixed_clock)
    transport = FakeTransport.instances[-1]
    inventory_ids = {
        device["deviceId"] for device in result.inventory.document["payload"]["devices"]
    }
    discovered_id = result.discovered.document["payload"]["deviceId"]

    assert transport.calls == [
        ("connect", None),
        ("publish", "GATEWAY_STATUS"),
        ("publish", "DEVICE_INVENTORY"),
        ("publish", "DEVICE_DISCOVERED"),
        ("publish", "GATEWAY_STATUS"),
        ("disconnect", None),
    ]
    assert discovered_id not in inventory_ids
    assert len(inventory_ids) == once_config.device_count
    assert result.online.document["payload"]["deviceCount"] == len(inventory_ids)
    assert result.offline.document["payload"]["deviceCount"] == len(inventory_ids) + 1
    assert [message.document["seq"] for message in result.messages] == [0, 1, 2, 3]
    assert all(message.document["bootId"] == result.boot_id for message in result.messages)


def test_duplicate_republishes_same_object_and_serialized_bytes(
    once_config: EmulatorConfig,
) -> None:
    result = run_duplicate(once_config, transport_factory=FakeTransport, clock=_fixed_clock)
    transport = FakeTransport.instances[-1]

    assert transport.calls == [
        ("connect", None),
        ("publish", "GATEWAY_STATUS"),
        ("publish", "SECURITY_EVENT"),
        ("publish", "SECURITY_EVENT"),
        ("publish", "GATEWAY_STATUS"),
        ("disconnect", None),
    ]
    assert transport.messages[1] is transport.messages[2]
    assert transport.messages[1].payload == transport.messages[2].payload
    assert transport.messages[1].topic == transport.messages[2].topic
    assert result.messages == tuple(transport.messages)
    assert [message.document["seq"] for message in result.messages] == [0, 1, 1, 2]
    assert all(message.document["bootId"] == result.boot_id for message in result.messages)


@pytest.mark.parametrize("runner", [run_anomaly, run_discover, run_duplicate])
def test_new_scenarios_abort_without_disconnect_on_body_publish_failure(
    once_config: EmulatorConfig,
    runner,
) -> None:
    FakeTransport.fail_message_type = (
        "SECURITY_EVENT" if runner != run_discover else "DEVICE_INVENTORY"
    )

    with pytest.raises(MqttTransportError, match="confirmation"):
        runner(once_config, transport_factory=FakeTransport, clock=_fixed_clock)

    transport = FakeTransport.instances[-1]
    assert transport.calls[-1] == ("abort", None)
    assert ("disconnect", None) not in transport.calls


def test_new_graceful_session_aborts_if_offline_is_not_acknowledged(
    once_config: EmulatorConfig,
) -> None:
    FakeTransport.fail_gateway_state = "OFFLINE"

    with pytest.raises(MqttTransportError, match="confirmation"):
        run_anomaly(once_config, transport_factory=FakeTransport, clock=_fixed_clock)

    transport = FakeTransport.instances[-1]
    assert transport.calls[-1] == ("abort", None)
    assert ("disconnect", None) not in transport.calls


def test_bounded_normal_publishes_periodically() -> None:
    config = EmulatorConfig(
        "broker.test",
        1883,
        "gw-emulator-01",
        "secret-value",
        device_count=1,
        discovered_device_count=0,
        telemetry_interval=1.0,
        duration=2.5,
    )
    sleeps: list[float] = []

    result = run_normal(
        config,
        transport_factory=FakeTransport,
        clock=_fixed_clock,
        sleeper=sleeps.append,
    )

    assert len(result.telemetry) == 3
    assert sleeps == [1.0, 1.0]
    assert [message.document["seq"] for message in result.messages] == list(range(6))


def test_seed_produces_same_telemetry_payloads(once_config: EmulatorConfig) -> None:
    first = run_normal(
        once_config,
        transport_factory=FakeTransport,
        clock=_fixed_clock,
        sleeper=_unexpected_sleep,
    )
    second = run_normal(
        once_config,
        transport_factory=FakeTransport,
        clock=_fixed_clock,
        sleeper=_unexpected_sleep,
    )

    assert first.telemetry[0].document["payload"] == second.telemetry[0].document["payload"]


def test_offline_publish_failure_aborts_without_graceful_disconnect(
    once_config: EmulatorConfig,
) -> None:
    FakeTransport.fail_gateway_state = "OFFLINE"

    with pytest.raises(MqttTransportError, match="confirmation"):
        run_normal(once_config, transport_factory=FakeTransport, clock=_fixed_clock)

    transport = FakeTransport.instances[-1]
    assert transport.calls[-1] == ("abort", None)
    assert ("disconnect", None) not in transport.calls
    assert transport.messages[-1].document["payload"]["state"] == "OFFLINE"


def test_connection_loss_between_telemetry_and_offline_prevents_offline_publish(
    once_config: EmulatorConfig,
) -> None:
    FakeTransport.lose_after_message_type = "TELEMETRY"

    with pytest.raises(MqttTransportError, match="lost"):
        run_normal(once_config, transport_factory=FakeTransport, clock=_fixed_clock)

    transport = FakeTransport.instances[-1]
    assert [message.document["type"] for message in transport.messages][-1] == "TELEMETRY"
    assert transport.calls[-1] == ("abort", None)
    assert ("disconnect", None) not in transport.calls


def _fixed_clock() -> datetime:
    return datetime(2026, 10, 21, 12, 0, tzinfo=UTC)


def _unexpected_sleep(seconds: float) -> None:
    raise AssertionError(f"unexpected sleep: {seconds}")
