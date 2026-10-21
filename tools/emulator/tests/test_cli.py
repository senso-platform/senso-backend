from __future__ import annotations

import pytest

from senso_emulator.__main__ import main
from senso_emulator.config import ConfigError
from senso_emulator.mqtt_transport import MqttTransportError


def test_cli_success_exit_code(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("SENSO_EMULATOR_PASSWORD", "secret-value")
    monkeypatch.setattr("senso_emulator.__main__.run_normal", lambda config: None)

    assert main(["normal", "--once"]) == 0


def test_offline_cli_success_exit_code(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("SENSO_EMULATOR_PASSWORD", "secret-value")
    monkeypatch.setattr("senso_emulator.__main__.run_offline", lambda config: None)

    assert main(["offline"]) == 0


@pytest.mark.parametrize(
    ("scenario", "runner_name"),
    [
        ("anomaly", "run_anomaly"),
        ("discover", "run_discover"),
        ("duplicate", "run_duplicate"),
    ],
)
def test_new_scenario_cli_success_exit_code(
    monkeypatch: pytest.MonkeyPatch,
    scenario: str,
    runner_name: str,
) -> None:
    monkeypatch.setenv("SENSO_EMULATOR_PASSWORD", "secret-value")
    monkeypatch.setattr(f"senso_emulator.__main__.{runner_name}", lambda config: None)

    assert main([scenario]) == 0


def test_cli_configuration_error_exit_code(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        "senso_emulator.__main__.load_config",
        lambda args: (_ for _ in ()).throw(ConfigError("invalid configuration")),
    )

    assert main(["normal", "--once"]) == 2


def test_cli_transport_error_exit_code(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("SENSO_EMULATOR_PASSWORD", "secret-value")
    monkeypatch.setattr(
        "senso_emulator.__main__.run_normal",
        lambda config: (_ for _ in ()).throw(MqttTransportError("network failure")),
    )

    assert main(["normal", "--once"]) == 3


def test_offline_cli_transport_error_exit_code(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("SENSO_EMULATOR_PASSWORD", "secret-value")
    monkeypatch.setattr(
        "senso_emulator.__main__.run_offline",
        lambda config: (_ for _ in ()).throw(MqttTransportError("abort failure")),
    )

    assert main(["offline"]) == 3


@pytest.mark.parametrize(
    ("scenario", "runner_name"),
    [
        ("anomaly", "run_anomaly"),
        ("discover", "run_discover"),
        ("duplicate", "run_duplicate"),
    ],
)
def test_new_scenario_transport_error_exit_code(
    monkeypatch: pytest.MonkeyPatch,
    scenario: str,
    runner_name: str,
) -> None:
    monkeypatch.setenv("SENSO_EMULATOR_PASSWORD", "secret-value")
    monkeypatch.setattr(
        f"senso_emulator.__main__.{runner_name}",
        lambda config: (_ for _ in ()).throw(MqttTransportError("network failure")),
    )

    assert main([scenario]) == 3
