from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

EMULATOR_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
MQTT_CONTRACTS = REPOSITORY_ROOT / "contracts" / "mqtt"
SRC = EMULATOR_ROOT / "src"

sys.path.insert(0, str(SRC))


@pytest.fixture(scope="session")
def envelope_schema() -> dict:
    return _load_schema("envelope.schema.json")


@pytest.fixture(scope="session")
def gateway_status_schema() -> dict:
    return _load_schema("payloads/gateway-status.schema.json")


@pytest.fixture(scope="session")
def device_inventory_schema() -> dict:
    return _load_schema("payloads/device-inventory.schema.json")


@pytest.fixture(scope="session")
def device_discovered_schema() -> dict:
    return _load_schema("payloads/device-discovered.schema.json")


@pytest.fixture(scope="session")
def telemetry_schema() -> dict:
    return _load_schema("payloads/telemetry.schema.json")


@pytest.fixture(scope="session")
def security_event_schema() -> dict:
    return _load_schema("payloads/security-event.schema.json")


def _load_schema(relative_path: str) -> dict:
    path = MQTT_CONTRACTS / relative_path
    if not path.is_file():
        pytest.fail(f"MQTT contract is missing: {path}")
    return json.loads(path.read_text(encoding="utf-8"))
