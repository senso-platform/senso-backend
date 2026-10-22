from __future__ import annotations

import pytest

from senso_emulator.config import ConfigError, load_config, parse_args


def test_loads_local_defaults_and_password_from_environment() -> None:
    args = parse_args(["normal", "--once"])

    config = load_config(args, {"SENSO_EMULATOR_PASSWORD": "secret-value"})

    assert config.host == "localhost"
    assert config.port == 1883
    assert config.gateway_id == "gw-emulator-01"
    assert config.keepalive == 30
    assert config.timeout == 10.0
    assert config.device_count == 2
    assert config.discovered_device_count == 1
    assert config.telemetry_interval == 1.0
    assert config.telemetry_batches == 1
    assert config.once is True
    assert config.password == "secret-value"
    assert "secret-value" not in repr(config)


def test_cli_overrides_non_secret_environment_settings() -> None:
    args = parse_args(
        [
            "normal",
            "--once",
            "--host",
            "broker.test",
            "--port",
            "2883",
            "--gateway-id",
            "gw-test-02",
            "--keepalive",
            "45",
            "--timeout",
            "3.5",
        ]
    )

    config = load_config(args, {"SENSO_EMULATOR_PASSWORD": "secret-value"})

    assert config.host == "broker.test"
    assert config.port == 2883
    assert config.gateway_id == "gw-test-02"
    assert config.keepalive == 45
    assert config.timeout == 3.5


def test_requires_password_environment_variable() -> None:
    args = parse_args(["normal", "--once"])

    with pytest.raises(ConfigError, match="SENSO_EMULATOR_PASSWORD is required"):
        load_config(args, {})


def test_loads_offline_connection_options() -> None:
    args = parse_args(
        [
            "offline",
            "--host",
            "broker.test",
            "--port",
            "2883",
            "--gateway-id",
            "gw-offline-01",
            "--keepalive",
            "15",
            "--timeout",
            "4",
        ]
    )

    config = load_config(args, {"SENSO_EMULATOR_PASSWORD": "secret-value"})

    assert args.scenario == "offline"
    assert config.host == "broker.test"
    assert config.port == 2883
    assert config.gateway_id == "gw-offline-01"
    assert config.keepalive == 15
    assert config.timeout == 4.0


@pytest.mark.parametrize("scenario", ["anomaly", "duplicate"])
def test_loads_event_scenario_connection_options(scenario: str) -> None:
    args = parse_args([scenario, "--gateway-id", "gw-event-01"])

    config = load_config(args, {"SENSO_EMULATOR_PASSWORD": "secret-value"})

    assert args.scenario == scenario
    assert config.gateway_id == "gw-event-01"


def test_discover_loads_initial_device_count() -> None:
    args = parse_args(["discover", "--devices", "4"])

    config = load_config(args, {"SENSO_EMULATOR_PASSWORD": "secret-value"})

    assert args.scenario == "discover"
    assert config.device_count == 4


def test_offline_rejects_password_cli_option_without_echoing_secret() -> None:
    with pytest.raises(ConfigError, match="SENSO_EMULATOR_PASSWORD") as error:
        parse_args(["offline", "--password=must-not-be-accepted"])

    assert "must-not-be-accepted" not in str(error.value)


@pytest.mark.parametrize("gateway_id", ["UPPERCASE", "ab", "gw_with_underscore"])
def test_rejects_gateway_id_outside_contract(gateway_id: str) -> None:
    args = parse_args(["normal", "--once", "--gateway-id", gateway_id])

    with pytest.raises(ConfigError, match="gateway ID"):
        load_config(args, {"SENSO_EMULATOR_PASSWORD": "secret-value"})


@pytest.mark.parametrize(
    "arguments",
    [
        ["--password", "must-not-be-accepted"],
        ["--password=must-not-be-accepted"],
        ["--passwd", "must-not-be-accepted"],
        ["--passwd=must-not-be-accepted"],
        ["-P", "must-not-be-accepted"],
        ["-Pmust-not-be-accepted"],
    ],
)
def test_password_has_no_cli_option(arguments: list[str]) -> None:
    with pytest.raises(ConfigError, match="SENSO_EMULATOR_PASSWORD") as error:
        parse_args(["normal", "--once", *arguments])

    assert "must-not-be-accepted" not in str(error.value)


def test_loads_bounded_normal_parameters_and_calculates_batches() -> None:
    args = parse_args(
        [
            "normal",
            "--devices",
            "3",
            "--discovered-devices",
            "2",
            "--interval",
            "2",
            "--duration",
            "5",
            "--seed",
            "42",
        ]
    )

    config = load_config(args, {"SENSO_EMULATOR_PASSWORD": "secret-value"})

    assert config.device_count == 3
    assert config.discovered_device_count == 2
    assert config.telemetry_interval == 2.0
    assert config.duration == 5.0
    assert config.telemetry_batches == 3
    assert config.seed == 42
    assert config.once is False


@pytest.mark.parametrize(
    ("arguments", "message"),
    [
        (["--devices", "0", "--discovered-devices", "0"], "total device count"),
        (["--devices", "101"], "device count"),
        (["--discovered-devices", "51"], "discovered device count"),
        (["--interval", "0"], "interval"),
        (["--interval", "nan"], "interval"),
        (["--duration", "0"], "duration"),
        (["--duration", "inf"], "duration"),
    ],
)
def test_rejects_invalid_normal_parameters(arguments: list[str], message: str) -> None:
    args = parse_args(["normal", *arguments])

    with pytest.raises(ConfigError, match=message):
        load_config(args, {"SENSO_EMULATOR_PASSWORD": "secret-value"})
