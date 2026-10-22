from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

import pytest
from jsonschema import Draft202012Validator, FormatChecker
from uuid6 import uuid7

from senso_emulator.messages import (
    Capability,
    Device,
    OutboundMessage,
    TelemetrySample,
    build_device_discovered,
    build_device_inventory,
    build_gateway_status,
    build_security_event,
    build_telemetry,
    format_utc_milliseconds,
)


@pytest.mark.parametrize("state", ["ONLINE", "DEGRADED", "OFFLINE"])
def test_gateway_status_matches_current_contract(
    state: str,
    envelope_schema: dict,
    gateway_status_schema: dict,
) -> None:
    message_id = uuid7()
    boot_id = str(uuid4())
    sent_at = datetime(2026, 10, 20, 12, 34, 56, 123456, tzinfo=UTC)

    message = build_gateway_status(
        gateway_id="gw-emulator-01",
        boot_id=boot_id,
        state=state,
        message_id=message_id,
        sent_at=sent_at,
    )

    validator = Draft202012Validator(envelope_schema, format_checker=FormatChecker())
    assert list(validator.iter_errors(message.document)) == []
    payload_validator = Draft202012Validator(gateway_status_schema, format_checker=FormatChecker())
    assert list(payload_validator.iter_errors(message.document["payload"])) == []
    assert UUID(message.document["messageId"]).version == 7
    assert message.document["bootId"] == boot_id
    assert message.document["sentAt"] == "2026-10-20T12:34:56.123Z"
    assert message.topic == "senso/v1/gw/gw-emulator-01/status"
    assert message.qos == 1
    assert message.retain is True
    assert json.loads(message.payload) == message.document


def test_online_and_offline_can_share_boot_id_but_have_distinct_message_ids() -> None:
    boot_id = str(uuid4())
    sent_at = datetime(2026, 10, 20, tzinfo=UTC)

    online = build_gateway_status(
        gateway_id="gw-emulator-01",
        boot_id=boot_id,
        state="ONLINE",
        message_id=uuid7(),
        sent_at=sent_at,
    )
    offline = build_gateway_status(
        gateway_id="gw-emulator-01",
        boot_id=boot_id,
        state="OFFLINE",
        message_id=uuid7(),
        sent_at=sent_at + timedelta(seconds=1),
    )

    assert online.document["bootId"] == offline.document["bootId"]
    assert online.document["messageId"] != offline.document["messageId"]


def test_rejects_non_v7_message_id() -> None:
    with pytest.raises(ValueError, match="UUID v7"):
        build_gateway_status(
            gateway_id="gw-emulator-01",
            boot_id=str(uuid4()),
            state="ONLINE",
            message_id=uuid4(),
            sent_at=datetime.now(UTC),
        )


def test_rejects_boot_id_outside_current_schema_pattern() -> None:
    with pytest.raises(ValueError, match="boot_id"):
        build_gateway_status(
            gateway_id="gw-emulator-01",
            boot_id="bad boot id",
            state="ONLINE",
            message_id=uuid7(),
            sent_at=datetime.now(UTC),
        )


def test_timestamp_must_be_timezone_aware() -> None:
    with pytest.raises(ValueError, match="timezone-aware"):
        format_utc_milliseconds(datetime(2026, 10, 20, 12, 0, 0))


def test_device_inventory_matches_current_contract(
    envelope_schema: dict,
    device_inventory_schema: dict,
) -> None:
    device = _device()
    message = build_device_inventory(
        gateway_id="gw-emulator-01",
        boot_id=str(uuid4()),
        devices=[device],
        message_id=uuid7(),
        sent_at=datetime(2026, 10, 20, 12, 0, tzinfo=UTC),
        seq=1,
    )

    _assert_matches_schemas(message, envelope_schema, device_inventory_schema)
    assert message.topic == "senso/v1/gw/gw-emulator-01/events"
    assert message.retain is False
    assert message.qos == 1
    assert message.document["seq"] == 1
    assert message.document["payload"]["devices"] == [
        {
            "deviceId": "sensor-001",
            "deviceType": "TEMPERATURE",
            "capabilities": [
                {"metric": "temperature", "unit": "CEL"},
                {"metric": "humidity", "unit": "PCT"},
            ],
        }
    ]


def test_device_discovered_matches_current_contract(
    envelope_schema: dict,
    device_discovered_schema: dict,
) -> None:
    message = build_device_discovered(
        gateway_id="gw-emulator-01",
        boot_id=str(uuid4()),
        device=_device(),
        message_id=uuid7(),
        sent_at=datetime(2026, 10, 20, 12, 0, tzinfo=UTC),
        seq=2,
    )

    _assert_matches_schemas(message, envelope_schema, device_discovered_schema)
    assert message.topic == "senso/v1/gw/gw-emulator-01/events"
    assert message.retain is False
    assert message.document["payload"]["metadata"] == {"firmware": "1.0.0"}


def test_telemetry_matches_current_contract_and_uses_utc_milliseconds(
    envelope_schema: dict,
    telemetry_schema: dict,
) -> None:
    message = build_telemetry(
        gateway_id="gw-emulator-01",
        boot_id=str(uuid4()),
        samples=[
            TelemetrySample(
                device_id="sensor-001",
                sampled_at=datetime(2026, 10, 20, 15, 30, 1, 123456, tzinfo=UTC),
                metrics={"temperature": 21.5, "humidity": 48.0},
            )
        ],
        message_id=uuid7(),
        sent_at=datetime(2026, 10, 20, 15, 30, 2, 987654, tzinfo=UTC),
        seq=3,
    )

    _assert_matches_schemas(message, envelope_schema, telemetry_schema)
    assert message.topic == "senso/v1/gw/gw-emulator-01/telemetry"
    assert message.retain is False
    assert message.document["sentAt"] == "2026-10-20T15:30:02.987Z"
    assert message.document["payload"]["samples"][0]["ts"] == "2026-10-20T15:30:01.123Z"
    assert UUID(message.document["messageId"]).version == 7


@pytest.mark.parametrize("device_id", [None, "sensor-001"])
def test_security_event_matches_current_contract_with_optional_device(
    device_id: str | None,
    envelope_schema: dict,
    security_event_schema: dict,
) -> None:
    detected_at = datetime(2026, 10, 20, 15, 30, 1, 123456, tzinfo=UTC)
    message = build_security_event(
        gateway_id="gw-emulator-01",
        boot_id=str(uuid4()),
        device_id=device_id,
        severity="CRITICAL",
        code="PHYSICAL_ANOMALY",
        message="Temperature is outside the physical range",
        detected_at=detected_at,
        detector_name="ids",
        detector_version="0.3.1",
        details={"metric": "temperature", "value": -500.0},
        message_id=uuid7(),
        sent_at=detected_at + timedelta(milliseconds=500),
        seq=4,
    )

    _assert_matches_schemas(message, envelope_schema, security_event_schema)
    assert message.topic == "senso/v1/gw/gw-emulator-01/events"
    assert message.qos == 1
    assert message.retain is False
    assert message.document["payload"]["detectedAt"] == "2026-10-20T15:30:01.123Z"
    assert ("deviceId" in message.document["payload"]) is (device_id is not None)


@pytest.mark.parametrize(
    ("changes", "error"),
    [
        ({"severity": "HIGH"}, "severity"),
        ({"code": "bad-code"}, "code"),
        ({"message": ""}, "message length"),
        ({"detector_name": None, "detector_version": "1"}, "requires detector_name"),
    ],
)
def test_security_event_rejects_values_outside_contract(
    changes: dict,
    error: str,
) -> None:
    arguments = {
        "gateway_id": "gw-emulator-01",
        "boot_id": str(uuid4()),
        "severity": "WARNING",
        "code": "TRAFFIC_SPIKE",
        "message": "Unexpected traffic",
        "detected_at": datetime.now(UTC),
        "message_id": uuid7(),
        "sent_at": datetime.now(UTC),
        "seq": 1,
    }
    arguments.update(changes)

    with pytest.raises(ValueError, match=error):
        build_security_event(**arguments)


def test_builders_reject_invalid_sequence_and_non_finite_metric() -> None:
    with pytest.raises(ValueError, match="seq"):
        build_device_inventory(
            gateway_id="gw-emulator-01",
            boot_id=str(uuid4()),
            devices=[],
            message_id=uuid7(),
            sent_at=datetime.now(UTC),
            seq=-1,
        )
    with pytest.raises(ValueError, match="finite"):
        build_telemetry(
            gateway_id="gw-emulator-01",
            boot_id=str(uuid4()),
            samples=[
                TelemetrySample(
                    "sensor-001",
                    datetime.now(UTC),
                    {"temperature": float("nan")},
                )
            ],
            message_id=uuid7(),
            sent_at=datetime.now(UTC),
            seq=1,
        )


def _device() -> Device:
    return Device(
        device_id="sensor-001",
        device_type="TEMPERATURE",
        capabilities=(
            Capability("temperature", "CEL"),
            Capability("humidity", "PCT"),
        ),
        metadata={"firmware": "1.0.0"},
    )


def _assert_matches_schemas(
    message: OutboundMessage,
    envelope_schema: dict,
    payload_schema: dict,
) -> None:
    document = message.document
    envelope_validator = Draft202012Validator(envelope_schema, format_checker=FormatChecker())
    assert list(envelope_validator.iter_errors(document)) == []
    payload_validator = Draft202012Validator(payload_schema, format_checker=FormatChecker())
    assert list(payload_validator.iter_errors(document["payload"])) == []
    assert UUID(document["messageId"]).version == 7
    assert document["sentAt"].endswith("Z")
    assert json.loads(message.payload) == document
