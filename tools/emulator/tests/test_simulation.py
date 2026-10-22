from __future__ import annotations

import random
from datetime import UTC, datetime

from senso_emulator.simulation import build_device_plan, generate_telemetry_samples


def test_inventory_and_discovered_devices_are_disjoint() -> None:
    plan = build_device_plan(2, 2)

    inventory_ids = {device.device_id for device in plan.inventory}
    discovered_ids = {device.device_id for device in plan.discovered}
    assert inventory_ids == {"sensor-001", "sensor-002"}
    assert discovered_ids == {"sensor-003", "sensor-004"}
    assert inventory_ids.isdisjoint(discovered_ids)


def test_seed_makes_telemetry_values_deterministic() -> None:
    plan = build_device_plan(2, 1)
    sampled_at = datetime(2026, 10, 21, tzinfo=UTC)

    first = generate_telemetry_samples(plan.all_devices, sampled_at, random.Random(42))
    second = generate_telemetry_samples(plan.all_devices, sampled_at, random.Random(42))

    assert first == second
    assert all(sample.sampled_at == sampled_at for sample in first)
