from __future__ import annotations

import base64
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from uuid import uuid4

import paho.mqtt.client as mqtt
import pytest

from senso_emulator.config import EmulatorConfig, load_config, parse_args
from senso_emulator.scenarios import (
    run_anomaly,
    run_discover,
    run_duplicate,
    run_normal,
    run_offline,
)

pytestmark = pytest.mark.integration
_NOT_FOUND = object()


def test_normal_once_delivers_complete_session_to_ingest_queue() -> None:
    if os.environ.get("SENSO_RUN_MQTT_INTEGRATION") != "1":
        pytest.skip("set SENSO_RUN_MQTT_INTEGRATION=1 to run broker integration tests")
    if os.environ.get("SENSO_MQTT_INTEGRATION_ISOLATED") != "1":
        pytest.skip("integration test requires an explicitly isolated disposable broker")

    admin_user = os.environ.get("SENSO_RABBIT_ADMIN_USER")
    admin_password = os.environ.get("SENSO_RABBIT_ADMIN_PASSWORD")
    if not admin_user or not admin_password:
        pytest.skip("RabbitMQ management credentials are not configured")

    config = load_config(parse_args(["normal", "--once"]))
    vhost = os.environ.get("SENSO_RABBIT_VHOST", "/")
    queue = "q.ingest.raw"
    routing_key = f"senso.v1.gw.{config.gateway_id}.*"
    if _queue_exists(admin_user, admin_password, vhost, queue):
        pytest.fail("refusing to use a pre-existing q.ingest.raw; broker is not disposable")

    queue_created = False
    try:
        _create_test_queue(admin_user, admin_password, vhost, queue)
        queue_created = True
        _bind_test_queue(admin_user, admin_password, vhost, queue, routing_key)
        result = run_normal(config)
        documents = _wait_for_messages(
            admin_user,
            admin_password,
            vhost,
            queue,
            timeout=config.timeout,
            expected_count=len(result.messages),
        )
    finally:
        if queue_created:
            _delete_test_queue(admin_user, admin_password, vhost, queue)

    expected_ids = {message.document["messageId"] for message in result.messages}
    owned = [document for document in documents if document.get("messageId") in expected_ids]
    assert len(owned) == len(result.messages)
    assert {document["messageId"] for document in owned} == expected_ids
    assert all(document["gatewayId"] == config.gateway_id for document in owned)
    assert all(document["bootId"] == result.boot_id for document in owned)
    assert owned == [message.document for message in result.messages]
    assert [document["seq"] for document in owned] == list(range(len(owned)))
    assert [document["type"] for document in owned] == [
        "GATEWAY_STATUS",
        "DEVICE_INVENTORY",
        "DEVICE_DISCOVERED",
        "TELEMETRY",
        "GATEWAY_STATUS",
    ]
    assert owned[0]["payload"]["state"] == "ONLINE"
    assert owned[-1]["payload"]["state"] == "OFFLINE"


def test_offline_emits_retained_last_will_and_next_boot_replaces_status() -> None:
    if os.environ.get("SENSO_RUN_MQTT_INTEGRATION") != "1":
        pytest.skip("set SENSO_RUN_MQTT_INTEGRATION=1 to run broker integration tests")
    if os.environ.get("SENSO_MQTT_INTEGRATION_ISOLATED") != "1":
        pytest.skip("integration test requires an explicitly isolated disposable broker")

    admin_user = os.environ.get("SENSO_RABBIT_ADMIN_USER")
    admin_password = os.environ.get("SENSO_RABBIT_ADMIN_PASSWORD")
    if not admin_user or not admin_password:
        pytest.skip("RabbitMQ management credentials are not configured")

    offline_config = load_config(parse_args(["offline"]))
    normal_config = load_config(parse_args(["normal", "--once"]))
    vhost = os.environ.get("SENSO_RABBIT_VHOST", "/")
    queue = "q.ingest.raw"
    routing_key = f"senso.v1.gw.{offline_config.gateway_id}.*"
    if _queue_exists(admin_user, admin_password, vhost, queue):
        pytest.fail("refusing to use a pre-existing q.ingest.raw; broker is not disposable")

    queue_created = False
    try:
        _create_test_queue(admin_user, admin_password, vhost, queue)
        queue_created = True
        _bind_test_queue(admin_user, admin_password, vhost, queue, routing_key)

        offline_result = run_offline(offline_config)
        offline_documents = _wait_for_messages(
            admin_user,
            admin_password,
            vhost,
            queue,
            timeout=offline_config.timeout,
            expected_count=2,
        )
        retained_will = _read_retained_status(
            offline_config,
            admin_user,
            admin_password,
        )
        _wait_for_gateway_connection_to_settle(
            admin_user,
            admin_password,
            offline_config.gateway_id,
            timeout=offline_config.timeout,
        )

        normal_result = run_normal(normal_config)
        normal_documents = _wait_for_messages(
            admin_user,
            admin_password,
            vhost,
            queue,
            timeout=normal_config.timeout,
            expected_count=len(normal_result.messages),
        )
        retained_after_normal = _read_retained_status(
            normal_config,
            admin_user,
            admin_password,
        )
    finally:
        if queue_created:
            _delete_test_queue(admin_user, admin_password, vhost, queue)

    assert offline_documents == [
        offline_result.online.document,
        offline_result.last_will.document,
    ]
    assert offline_documents[1]["type"] == "GATEWAY_STATUS"
    assert offline_documents[1]["payload"]["state"] == "OFFLINE"
    assert offline_documents[1]["bootId"] == offline_result.online.document["bootId"]
    assert offline_documents[1]["messageId"] == offline_result.last_will.document["messageId"]

    retained_will_document = json.loads(retained_will.payload)
    assert retained_will.topic == offline_result.last_will.topic
    assert retained_will.qos == 1
    assert retained_will.retain is True
    assert retained_will_document == offline_result.last_will.document

    assert normal_result.boot_id != offline_result.boot_id
    assert normal_documents == [message.document for message in normal_result.messages]
    assert normal_documents[0]["payload"]["state"] == "ONLINE"
    assert normal_documents[0]["bootId"] == normal_result.boot_id
    assert normal_documents[-1]["payload"]["state"] == "OFFLINE"
    assert normal_documents[-1]["bootId"] == normal_result.boot_id

    retained_after_normal_document = json.loads(retained_after_normal.payload)
    assert retained_after_normal.topic == normal_result.offline.topic
    assert retained_after_normal.qos == 1
    assert retained_after_normal.retain is True
    assert retained_after_normal_document == normal_result.offline.document


def test_event_scenarios_deliver_contract_messages_and_exact_duplicate_bytes() -> None:
    if os.environ.get("SENSO_RUN_MQTT_INTEGRATION") != "1":
        pytest.skip("set SENSO_RUN_MQTT_INTEGRATION=1 to run broker integration tests")
    if os.environ.get("SENSO_MQTT_INTEGRATION_ISOLATED") != "1":
        pytest.skip("integration test requires an explicitly isolated disposable broker")

    admin_user = os.environ.get("SENSO_RABBIT_ADMIN_USER")
    admin_password = os.environ.get("SENSO_RABBIT_ADMIN_PASSWORD")
    if not admin_user or not admin_password:
        pytest.skip("RabbitMQ management credentials are not configured")

    config = load_config(parse_args(["anomaly"]))
    vhost = os.environ.get("SENSO_RABBIT_VHOST", "/")
    queue = "q.ingest.raw"
    routing_key = f"senso.v1.gw.{config.gateway_id}.*"
    if _queue_exists(admin_user, admin_password, vhost, queue):
        pytest.fail("refusing to use a pre-existing q.ingest.raw; broker is not disposable")

    queue_created = False
    try:
        _create_test_queue(admin_user, admin_password, vhost, queue)
        queue_created = True
        _bind_test_queue(admin_user, admin_password, vhost, queue, routing_key)

        anomaly = run_anomaly(config)
        anomaly_payloads = _wait_for_payloads(
            admin_user,
            admin_password,
            vhost,
            queue,
            timeout=config.timeout,
            expected_count=len(anomaly.messages),
        )
        _wait_for_gateway_connection_to_settle(
            admin_user, admin_password, config.gateway_id, timeout=config.timeout
        )

        discover = run_discover(config)
        discover_payloads = _wait_for_payloads(
            admin_user,
            admin_password,
            vhost,
            queue,
            timeout=config.timeout,
            expected_count=len(discover.messages),
        )
        _wait_for_gateway_connection_to_settle(
            admin_user, admin_password, config.gateway_id, timeout=config.timeout
        )

        duplicate = run_duplicate(config)
        duplicate_payloads = _wait_for_payloads(
            admin_user,
            admin_password,
            vhost,
            queue,
            timeout=config.timeout,
            expected_count=len(duplicate.messages),
        )
    finally:
        if queue_created:
            _delete_test_queue(admin_user, admin_password, vhost, queue)

    assert [json.loads(payload) for payload in anomaly_payloads] == [
        message.document for message in anomaly.messages
    ]
    assert anomaly.event.document["type"] == "SECURITY_EVENT"
    assert anomaly.event.document["payload"]["severity"] == "WARNING"

    assert [json.loads(payload) for payload in discover_payloads] == [
        message.document for message in discover.messages
    ]
    inventory_ids = {
        device["deviceId"] for device in discover.inventory.document["payload"]["devices"]
    }
    assert discover.discovered.document["payload"]["deviceId"] not in inventory_ids

    assert [json.loads(payload) for payload in duplicate_payloads] == [
        message.document for message in duplicate.messages
    ]
    assert duplicate_payloads[1] == duplicate_payloads[2] == duplicate.duplicate.payload


def _read_retained_status(
    config: EmulatorConfig,
    username: str,
    password: str,
) -> mqtt.MQTTMessage:
    topic = f"senso/v1/gw/{config.gateway_id}/status"
    messages: list[mqtt.MQTTMessage] = []
    callback_errors: list[str] = []
    client = mqtt.Client(
        callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
        client_id=f"integration-observer-{uuid4()}",
        protocol=mqtt.MQTTv311,
        reconnect_on_failure=False,
    )
    client.connect_timeout = config.timeout
    client.username_pw_set(username, password)

    def on_connect(
        connected_client: mqtt.Client,
        userdata: object,
        flags: mqtt.ConnectFlags,
        reason_code: mqtt.ReasonCode,
        properties: mqtt.Properties | None,
    ) -> None:
        del userdata, flags, properties
        if reason_code.is_failure:
            callback_errors.append(f"observer connection rejected: {reason_code}")
            return
        result, _ = connected_client.subscribe(topic, qos=1)
        if result != mqtt.MQTT_ERR_SUCCESS:
            callback_errors.append(f"observer subscribe failed with code {result}")

    def on_message(
        connected_client: mqtt.Client,
        userdata: object,
        message: mqtt.MQTTMessage,
    ) -> None:
        del connected_client, userdata
        messages.append(message)

    client.on_connect = on_connect
    client.on_message = on_message
    connect_result = client.connect(config.host, config.port, keepalive=config.keepalive)
    if connect_result != mqtt.MQTT_ERR_SUCCESS:
        pytest.fail(f"observer connect failed with code {connect_result}")

    deadline = time.monotonic() + config.timeout
    while not messages and not callback_errors and time.monotonic() < deadline:
        loop_result = client.loop(timeout=0.1)
        if loop_result != mqtt.MQTT_ERR_SUCCESS:
            pytest.fail(f"observer network loop failed with code {loop_result}")

    disconnect_result = client.disconnect()
    if disconnect_result != mqtt.MQTT_ERR_SUCCESS:
        pytest.fail(f"observer disconnect failed with code {disconnect_result}")
    if callback_errors:
        pytest.fail(callback_errors[0])
    if not messages:
        pytest.fail(f"retained status was not received from {topic}")
    return messages[0]


def _wait_for_gateway_connection_to_settle(
    username: str,
    password: str,
    gateway_id: str,
    *,
    timeout: float,
) -> None:
    deadline = time.monotonic() + timeout
    absent_since: float | None = None
    while time.monotonic() < deadline:
        connections = _request(username, password, "GET", "/api/connections")
        gateway_connected = any(connection.get("user") == gateway_id for connection in connections)
        now = time.monotonic()
        if gateway_connected:
            absent_since = None
        elif absent_since is None:
            absent_since = now
        elif now - absent_since >= 0.25:
            return
        time.sleep(0.05)
    pytest.fail(f"MQTT connection for {gateway_id} did not settle before the timeout")


def _queue_exists(username: str, password: str, vhost: str, queue: str) -> bool:
    quoted_vhost = urllib.parse.quote(vhost, safe="")
    quoted_queue = urllib.parse.quote(queue, safe="")
    result = _request(
        username,
        password,
        "GET",
        f"/api/queues/{quoted_vhost}/{quoted_queue}",
        allow_not_found=True,
    )
    return result is not _NOT_FOUND


def _create_test_queue(username: str, password: str, vhost: str, queue: str) -> None:
    quoted_vhost = urllib.parse.quote(vhost, safe="")
    quoted_queue = urllib.parse.quote(queue, safe="")
    _request(
        username,
        password,
        "PUT",
        f"/api/queues/{quoted_vhost}/{quoted_queue}",
        {"durable": True, "auto_delete": False, "arguments": {}},
    )


def _bind_test_queue(
    username: str,
    password: str,
    vhost: str,
    queue: str,
    routing_key: str,
) -> None:
    quoted_vhost = urllib.parse.quote(vhost, safe="")
    quoted_queue = urllib.parse.quote(queue, safe="")
    _request(
        username,
        password,
        "POST",
        f"/api/bindings/{quoted_vhost}/e/amq.topic/q/{quoted_queue}",
        {"routing_key": routing_key, "arguments": {}},
    )


def _wait_for_messages(
    username: str,
    password: str,
    vhost: str,
    queue: str,
    *,
    timeout: float,
    expected_count: int,
) -> list[dict]:
    return [
        json.loads(payload)
        for payload in _wait_for_payloads(
            username,
            password,
            vhost,
            queue,
            timeout=timeout,
            expected_count=expected_count,
        )
    ]


def _wait_for_payloads(
    username: str,
    password: str,
    vhost: str,
    queue: str,
    *,
    timeout: float,
    expected_count: int,
) -> list[bytes]:
    quoted_vhost = urllib.parse.quote(vhost, safe="")
    quoted_queue = urllib.parse.quote(queue, safe="")
    payloads: list[bytes] = []
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline and len(payloads) < expected_count:
        messages = _request(
            username,
            password,
            "POST",
            f"/api/queues/{quoted_vhost}/{quoted_queue}/get",
            {"count": 10, "ackmode": "ack_requeue_false", "encoding": "base64"},
        )
        for message in messages:
            payloads.append(base64.b64decode(message["payload"]))
        if len(payloads) < expected_count:
            time.sleep(0.1)
    if len(payloads) < expected_count:
        pytest.fail(f"q.ingest.raw did not receive {expected_count} messages before the timeout")
    return payloads


def _delete_test_queue(username: str, password: str, vhost: str, queue: str) -> None:
    quoted_vhost = urllib.parse.quote(vhost, safe="")
    quoted_queue = urllib.parse.quote(queue, safe="")
    _request(username, password, "DELETE", f"/api/queues/{quoted_vhost}/{quoted_queue}")


def _request(
    username: str,
    password: str,
    method: str,
    path: str,
    document: dict | None = None,
    *,
    allow_not_found: bool = False,
) -> object:
    base_url = os.environ.get("SENSO_RABBIT_MANAGEMENT_URL", "http://localhost:15672")
    body = None if document is None else json.dumps(document).encode("utf-8")
    request = urllib.request.Request(f"{base_url.rstrip('/')}{path}", data=body, method=method)
    token = base64.b64encode(f"{username}:{password}".encode()).decode("ascii")
    request.add_header("Authorization", f"Basic {token}")
    request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            response_body = response.read()
            if not response_body:
                return None
            return json.loads(response_body)
    except urllib.error.HTTPError as exc:
        if allow_not_found and exc.code == 404:
            return _NOT_FOUND
        pytest.fail(f"RabbitMQ management request {method} {path} failed: {exc}")
    except OSError as exc:
        pytest.fail(f"RabbitMQ management request {method} {path} failed: {exc}")
