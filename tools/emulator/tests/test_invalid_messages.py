from __future__ import annotations

import copy
import json
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from jsonschema import Draft202012Validator, FormatChecker
from uuid6 import uuid7

import senso_emulator.invalid_messages as invalid_messages
from senso_emulator.invalid_messages import (
    FUTURE_TIMESTAMP_OFFSET,
    INGEST_MAX_MESSAGE_BYTES,
    INVALID_MESSAGE_VARIANTS,
    RABBITMQ_MAX_MESSAGE_BYTES,
    build_invalid_message,
)
from senso_emulator.messages import OutboundMessage, TelemetrySample, build_telemetry

GATEWAY_ID = "gw-emulator-01"
SENT_AT = datetime(2026, 11, 5, 12, 0, 0, tzinfo=UTC)


@pytest.mark.parametrize("variant", sorted(INVALID_MESSAGE_VARIANTS))
def test_invalid_variants_keep_mqtt_delivery_settings(variant: str) -> None:
    message = _build(variant)

    assert message.topic == f"senso/v1/gw/{GATEWAY_ID}/telemetry"
    assert message.qos == 1
    assert message.retain is False


def test_broken_json_contains_truncated_valid_message() -> None:
    message = _build("broken-json")

    with pytest.raises(json.JSONDecodeError):
        json.loads(message.payload)
    assert message.payload == _serialized(message.document)[:-1]


def test_missing_message_id_only_breaks_required_envelope_field(
    envelope_schema: dict,
    telemetry_schema: dict,
) -> None:
    message = _build("missing-message-id")

    errors = _errors(envelope_schema, message.document)
    assert len(errors) == 1
    assert errors[0].validator == "required"
    assert "messageId" in errors[0].message
    assert _errors(telemetry_schema, message.document["payload"]) == []
    assert "messageId" not in json.loads(message.payload)


def test_wrong_type_matches_contract_invalid_example_semantics(
    envelope_schema: dict,
    telemetry_schema: dict,
) -> None:
    message = _build("wrong-type")

    errors = _errors(envelope_schema, message.document)
    assert len(errors) == 1
    assert errors[0].validator == "enum"
    assert message.document["type"] == "DEVICE_PING"
    assert _errors(telemetry_schema, message.document["payload"]) == []


def test_too_many_samples_only_exceeds_payload_array_limit(
    envelope_schema: dict,
    telemetry_schema: dict,
) -> None:
    message = _build("too-many-samples")

    assert _errors(envelope_schema, message.document) == []
    errors = _errors(telemetry_schema, message.document["payload"])
    assert len(errors) == 1
    assert errors[0].validator == "maxItems"
    assert len(message.document["payload"]["samples"]) == 501


def test_future_timestamp_is_schema_valid_but_exceeds_ingest_skew(
    envelope_schema: dict,
    telemetry_schema: dict,
) -> None:
    message = _build("future-timestamp")

    assert _errors(envelope_schema, message.document) == []
    assert _errors(telemetry_schema, message.document["payload"]) == []
    assert datetime.fromisoformat(message.document["sentAt"].replace("Z", "+00:00")) == (
        SENT_AT + FUTURE_TIMESTAMP_OFFSET
    )
    assert timedelta(minutes=5) < FUTURE_TIMESTAMP_OFFSET
    assert message.document["payload"]["samples"][0]["ts"] == "2026-11-05T12:00:00.000Z"


def test_gateway_mismatch_changes_only_envelope_gateway(
    envelope_schema: dict,
    telemetry_schema: dict,
) -> None:
    message = _build("gateway-mismatch")

    assert _errors(envelope_schema, message.document) == []
    assert _errors(telemetry_schema, message.document["payload"]) == []
    assert message.topic == f"senso/v1/gw/{GATEWAY_ID}/telemetry"
    assert message.document["gatewayId"] == "gw-mismatch-01"


def test_oversized_is_schema_valid_and_fits_between_ingest_and_broker_limits(
    envelope_schema: dict,
    telemetry_schema: dict,
) -> None:
    message = _build("oversized")

    assert _errors(envelope_schema, message.document) == []
    assert _errors(telemetry_schema, message.document["payload"]) == []
    assert INGEST_MAX_MESSAGE_BYTES < len(message.payload) < RABBITMQ_MAX_MESSAGE_BYTES
    assert len(message.document["payload"]["samples"]) == 500


def test_mutation_does_not_change_builder_result(monkeypatch: pytest.MonkeyPatch) -> None:
    valid = build_telemetry(
        gateway_id=GATEWAY_ID,
        boot_id=str(uuid4()),
        samples=[TelemetrySample("sensor-000", SENT_AT, {"temperature": 21.5})],
        message_id=uuid7(),
        sent_at=SENT_AT,
        seq=1,
    )
    original_document = copy.deepcopy(valid.document)
    original_payload = valid.payload
    monkeypatch.setattr(invalid_messages, "build_telemetry", lambda **kwargs: valid)

    mutated = build_invalid_message(
        "gateway-mismatch",
        gateway_id=GATEWAY_ID,
        boot_id=str(uuid4()),
        message_id=uuid7(),
        sent_at=SENT_AT,
    )

    assert mutated.document is not valid.document
    assert valid.document == original_document
    assert valid.payload == original_payload


def test_rejects_unknown_variant() -> None:
    with pytest.raises(ValueError, match="unsupported invalid message variant"):
        _build("unknown")


def _build(variant: str) -> OutboundMessage:
    return build_invalid_message(
        variant,
        gateway_id=GATEWAY_ID,
        boot_id=str(uuid4()),
        message_id=uuid7(),
        sent_at=SENT_AT,
        seq=1,
    )


def _errors(schema: dict, instance: object) -> list:
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    return list(validator.iter_errors(instance))


def _serialized(document: dict) -> bytes:
    return json.dumps(
        document,
        ensure_ascii=False,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
