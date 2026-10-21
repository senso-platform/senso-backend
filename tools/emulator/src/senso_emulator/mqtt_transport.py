from __future__ import annotations

import logging
import socket
import threading

import paho.mqtt.client as mqtt

from senso_emulator.config import EmulatorConfig
from senso_emulator.messages import OutboundMessage


class MqttTransportError(RuntimeError):
    """A safe-to-display MQTT transport error."""


LOGGER = logging.getLogger(__name__)


class MqttTransport:
    def __init__(self, config: EmulatorConfig, last_will: OutboundMessage) -> None:
        self._config = config
        self._connack_finished = threading.Event()
        self._connected = threading.Event()
        self._disconnected = threading.Event()
        self._lifecycle_lock = threading.Lock()
        self._connection_error: str | None = None
        self._loop_started = False
        self._graceful_disconnect_requested = False
        self._graceful_disconnect_confirmed = False
        self._closed = False

        self._client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=config.gateway_id,
            protocol=mqtt.MQTTv311,
            reconnect_on_failure=False,
        )
        self._client.connect_timeout = config.timeout
        self._client.username_pw_set(config.gateway_id, config.password)
        self._client.will_set(
            last_will.topic,
            payload=last_will.payload,
            qos=last_will.qos,
            retain=last_will.retain,
        )
        self._client.on_connect = self._on_connect
        self._client.on_disconnect = self._on_disconnect

    def connect(self) -> None:
        if self._closed:
            raise MqttTransportError("MQTT transport is closed")
        try:
            result = self._client.connect(
                self._config.host,
                self._config.port,
                keepalive=self._config.keepalive,
            )
            if result != mqtt.MQTT_ERR_SUCCESS:
                raise MqttTransportError(f"MQTT connect failed with code {result}")
            loop_result = self._client.loop_start()
            if loop_result != mqtt.MQTT_ERR_SUCCESS:
                raise MqttTransportError(f"MQTT network loop failed with code {loop_result}")
            self._loop_started = True
        except MqttTransportError:
            self.abort()
            raise
        except (OSError, RuntimeError, ValueError) as exc:
            error = MqttTransportError(
                f"cannot connect to MQTT broker {self._config.host}:{self._config.port}"
            )
            self.abort()
            raise error from exc

        if not self._connack_finished.wait(self._config.timeout):
            error = MqttTransportError("timed out waiting for MQTT CONNACK")
            self.abort()
            raise error
        if self._connection_error is not None or not self._connected.is_set():
            error = MqttTransportError(
                self._connection_error or "MQTT connection was lost before CONNACK"
            )
            self.abort()
            raise error

    def publish(self, message: OutboundMessage) -> int:
        if not self._connected.is_set() or self._connection_error is not None:
            raise MqttTransportError(self._connection_error or "MQTT client is not connected")

        try:
            info = self._client.publish(
                message.topic,
                payload=message.payload,
                qos=message.qos,
                retain=message.retain,
            )
        except (RuntimeError, ValueError) as exc:
            raise MqttTransportError("MQTT publish could not be started") from exc
        if info.rc != mqtt.MQTT_ERR_SUCCESS:
            raise MqttTransportError(f"MQTT publish failed with code {info.rc}")
        try:
            info.wait_for_publish(timeout=self._config.timeout)
            published = info.is_published()
        except (RuntimeError, ValueError) as exc:
            raise MqttTransportError("MQTT publish confirmation failed") from exc
        if self._connection_error is not None or not self._connected.is_set():
            raise MqttTransportError(self._connection_error or "MQTT connection was lost")
        if not published:
            raise MqttTransportError(f"timed out waiting for PUBACK for message {info.mid}")
        return info.mid

    def disconnect_gracefully(self) -> None:
        if self._closed:
            return
        if not self._connected.is_set() or self._connection_error is not None:
            error = MqttTransportError(
                self._connection_error or "MQTT connection was lost before DISCONNECT"
            )
            self.abort()
            raise error

        with self._lifecycle_lock:
            self._graceful_disconnect_requested = True
        try:
            result = self._client.disconnect()
            if result == mqtt.MQTT_ERR_NO_CONN:
                self._disconnected.wait(self._config.timeout)
                with self._lifecycle_lock:
                    confirmed = self._graceful_disconnect_confirmed
                    connection_error = self._connection_error
                if not confirmed:
                    detail = f"MQTT disconnect failed with code {result}"
                    if connection_error is not None:
                        detail = f"{detail}: {connection_error}"
                    raise MqttTransportError(detail)
            elif result != mqtt.MQTT_ERR_SUCCESS:
                raise MqttTransportError(f"MQTT disconnect failed with code {result}")
            if not self._disconnected.wait(self._config.timeout):
                raise MqttTransportError("timed out waiting for MQTT DISCONNECT")
            with self._lifecycle_lock:
                confirmed = self._graceful_disconnect_confirmed
                connection_error = self._connection_error
            if not confirmed:
                raise MqttTransportError(
                    connection_error or "MQTT connection was lost during DISCONNECT"
                )
        except MqttTransportError:
            self.abort()
            raise
        except (OSError, RuntimeError, ValueError) as exc:
            error = MqttTransportError("MQTT disconnect failed")
            self.abort()
            raise error from exc

        loop_error = self._stop_loop(allow_already_stopped=True)
        self._closed = True
        self._connected.clear()
        if loop_error is not None:
            self._close_socket()
            raise loop_error

    def abort(self) -> MqttTransportError | None:
        """Close the TCP connection without MQTT DISCONNECT so the broker can emit the Will."""
        if self._closed:
            return None

        broker_socket = self._get_socket()
        socket_error = self._close_socket(broker_socket)
        socket_closed = broker_socket is not None and socket_error is None
        loop_error = self._stop_loop(allow_already_stopped=socket_closed)
        self._connected.clear()
        self._closed = True
        for cleanup_error in (loop_error, socket_error):
            if cleanup_error is not None:
                LOGGER.warning("MQTT abort cleanup failed: %s", cleanup_error)
        return loop_error or socket_error

    def close(self) -> None:
        """Idempotent emergency cleanup; never sends an MQTT DISCONNECT packet."""
        self.abort()

    def _stop_loop(self, *, allow_already_stopped: bool = False) -> MqttTransportError | None:
        if not self._loop_started:
            return None
        self._loop_started = False
        try:
            result = self._client.loop_stop()
        except (OSError, RuntimeError, ValueError) as exc:
            error = MqttTransportError("MQTT network loop could not be stopped")
            error.__cause__ = exc
            return error
        if result == mqtt.MQTT_ERR_INVAL and allow_already_stopped:
            return None
        if result != mqtt.MQTT_ERR_SUCCESS:
            return MqttTransportError(f"MQTT network loop stop failed with code {result}")
        return None

    def _get_socket(self) -> socket.socket | None:
        try:
            return self._client.socket()
        except (OSError, RuntimeError, ValueError) as exc:
            LOGGER.warning("could not access MQTT socket during abort: %s", type(exc).__name__)
            return None

    def _close_socket(
        self,
        broker_socket: socket.socket | None = None,
    ) -> MqttTransportError | None:
        broker_socket = broker_socket if broker_socket is not None else self._get_socket()
        if broker_socket is None:
            return None
        first_error: OSError | None = None
        try:
            broker_socket.shutdown(socket.SHUT_RDWR)
        except OSError as exc:
            first_error = exc
        try:
            broker_socket.close()
        except OSError as exc:
            first_error = first_error or exc
        if first_error is not None:
            return MqttTransportError("MQTT socket could not be closed")
        return None

    def _on_connect(
        self,
        client: mqtt.Client,
        userdata: object,
        flags: mqtt.ConnectFlags,
        reason_code: mqtt.ReasonCode,
        properties: mqtt.Properties | None,
    ) -> None:
        del client, userdata, flags, properties
        if reason_code.is_failure:
            self._connection_error = f"MQTT broker rejected the connection: {reason_code}"
        else:
            self._connected.set()
        self._connack_finished.set()

    def _on_disconnect(
        self,
        client: mqtt.Client,
        userdata: object,
        disconnect_flags: mqtt.DisconnectFlags,
        reason_code: mqtt.ReasonCode,
        properties: mqtt.Properties | None,
    ) -> None:
        del client, userdata, properties
        self._connected.clear()
        server_disconnect = disconnect_flags.is_disconnect_packet_from_server
        with self._lifecycle_lock:
            graceful = (
                self._graceful_disconnect_requested
                and not reason_code.is_failure
                and not server_disconnect
            )
            if graceful:
                self._graceful_disconnect_confirmed = True
            elif self._connection_error is None:
                self._connection_error = f"MQTT connection was lost: {reason_code}"
        self._connack_finished.set()
        self._disconnected.set()
