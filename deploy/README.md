# deploy

Локальная инфраструктура: PostgreSQL + TimescaleDB и RabbitMQ с MQTT.

```bash
cp deploy/.env.example deploy/.env
docker compose -f deploy/docker-compose.yml up -d
docker compose -f deploy/docker-compose.yml ps        # оба сервиса должны быть healthy
```

| Что | Где |
| --- | --- |
| PostgreSQL | `localhost:5432`, БД/пользователь/пароль из `.env` |
| RabbitMQ UI | http://localhost:15672, `senso-admin` / пароль из `.env` |
| MQTT | `localhost:1883`, логин `gw-emulator-01` |

Порты публикует compose по значениям из `deploy/.env` (`SENSO_DB_PORT` и т. п.); в репозитории везде канон
5432/5672/15672/1883. Если порт на хосте уже занят (на Windows свою службу PostgreSQL видно как
`postgresql-x64-<major>`, и `127.0.0.1:5432` будет отвечать ей, а не контейнеру), поменяй порт только в своём
`deploy/.env` — трекаемые файлы не правятся. Проверить, кто держит порт: `netstat -ano | findstr :5432`
и `docker port senso-postgres-1`. Приложение этот файл не читает, ему тот же порт передают через
`SENSO_DB_URL` в окружении запуска.

Проверка MQTT (нужен `mosquitto-clients`):

```bash
mosquitto_pub -h localhost -p 1883 -q 1 -u gw-emulator-01 -P emulator-dev-pass \
  -i gw-emulator-01 -t senso/v1/gw/gw-emulator-01/telemetry -m '{"hello":"world"}'
```

Без установленного `mosquitto-clients` (Windows) — тем же клиентом из образа `eclipse-mosquitto:2`.
**JSON через `-m` из Windows PowerShell 5.1 не передавать:** он выкидывает внутренние двойные кавычки у
аргументов внешних программ, и `{"hello":"world"}` доходит как `{hello:world}` (13 байт, `MALFORMED_JSON`).
Сообщения шлём из файлов (`-f`), монтируя каталоги примеров в контейнер.

### Ручная проверка ingest

Нужны `rabbitmq` из compose и запущенный `ingest` (профиль `local`). Команды — из корня репозитория.

PowerShell:

```powershell
function pub($channel, $file) {
  docker run --rm -v "${PWD}/contracts/mqtt/examples:/ex:ro" -v "${PWD}/deploy/mqtt-samples:/samples:ro" `
    eclipse-mosquitto:2 mosquitto_pub -h host.docker.internal -p 1883 -q 1 `
    -u gw-emulator-01 -P emulator-dev-pass -i gw-emulator-01 -t "senso/v1/gw/gw-emulator-01/$channel" -f $file
}
pub telemetry /ex/valid/telemetry.json
```

bash / Git Bash (`MSYS_NO_PATHCONV=1` нужен только в Git Bash):

```bash
pub() {
  MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD/contracts/mqtt/examples:/ex:ro" -v "$PWD/deploy/mqtt-samples:/samples:ro" \
    eclipse-mosquitto:2 mosquitto_pub -h host.docker.internal -p 1883 -q 1 \
    -u gw-emulator-01 -P emulator-dev-pass -i gw-emulator-01 -t "senso/v1/gw/gw-emulator-01/$1" -f "$2"
}
pub telemetry /ex/valid/telemetry.json
```

Пока в `core` нет потребителей, валидным сообщениям некуда идти: они уходят в `q.dlq.failed` после 4 попыток.
Чтобы увидеть их в `senso.validated`, на время проверки создай приёмник: RabbitMQ UI → Queues → Add queue
`q.check.validated` (type quorum, durable) → Bindings: from exchange `senso.validated`, routing key `#`.
После проверки удали очередь.

| Команда | Ожидаемый результат (`x-reject-reason` в `q.dlq.invalid`) |
| --- | --- |
| `pub telemetry /ex/valid/telemetry.json` | `senso.validated`, ключ `telemetry.v1` |
| `pub events /ex/valid/device-discovered.json` | ключ `device.discovered.v1` |
| `pub events /ex/valid/device-inventory.json` | ключ `device.inventory.v1` |
| `pub events /ex/valid/security-event.json` | ключ `security.event.v1` |
| `pub status /ex/valid/gateway-status.json` (и `gateway-status-lwt.json`) | ключ `gateway.status.v1` |
| то же сообщение второй раз | второе сообщение с тем же `x-message-id`: дубли отсекает `core`, не ingest |
| `pub telemetry /ex/invalid/missing-message-id.json` | `ENVELOPE_SCHEMA` |
| `pub telemetry /ex/invalid/wrong-type.json` | `ENVELOPE_SCHEMA` (тип вне enum) |
| `pub telemetry /ex/invalid/too-many-samples.json` | `PAYLOAD_SCHEMA` |
| `pub telemetry /ex/invalid/future-timestamp.json` | `TIMESTAMP_IN_FUTURE` |
| `pub telemetry /samples/hello-world.json` | `ENVELOPE_SCHEMA` (JSON, но не конверт) |
| `pub telemetry /samples/json-array.json` | `MALFORMED_JSON` |
| `pub telemetry /samples/gateway-mismatch.json` | `GATEWAY_MISMATCH` |
| `pub telemetry /samples/sent-at-not-a-date.json` | `ENVELOPE_SCHEMA` (format `date-time`) |
| `pub telemetry /samples/sample-ts-in-future.json` | `TIMESTAMP_IN_FUTURE` (`payload.samples[0].ts`) |
| `pub status /samples/gateway-status-time-synced.json` | `PAYLOAD_SCHEMA` (`timeSynced` нет в схеме v1) |
| `pub status /ex/valid/telemetry.json` | `CHANNEL_TYPE_MISMATCH` |
| `pub ack /ex/valid/telemetry.json` | `UNKNOWN_CHANNEL` |
| пустое сообщение (`mosquitto_pub … -n` вместо `-f`) | `MALFORMED_JSON` |
| сообщение 300 КБ (команда ниже) | `PAYLOAD_TOO_LARGE` |
| `-t senso/v1/gw/gw-other-01/telemetry` | брокер рвёт соединение (ACL), `Error: The connection was lost`, до ingest не доходит |
| валидное без приёмника на `senso.validated` | 4 попытки (`ingest_messages_total{result="error"}`), затем `q.dlq.failed` с `x-first-death-reason: rejected` |

Сообщение 300 КБ (одинаково в PowerShell и bash):

```bash
docker run --rm eclipse-mosquitto:2 sh -c "head -c 300000 /dev/zero | tr '\0' a | mosquitto_pub -h host.docker.internal -p 1883 -q 1 -u gw-emulator-01 -P emulator-dev-pass -i gw-emulator-01 -t senso/v1/gw/gw-emulator-01/telemetry -s"
```

`ROUTING_KEY` и `SIGNATURE_INVALID` через MQTT не получить: кривой топик не проходит ACL и binding
`senso.v1.gw.*.*`, проверка подписи выключена (`senso.ingest.signature-required=false`). Оба случая покрыты
unit-тестами. Счётчики по всем исходам: http://localhost:8082/actuator/prometheus (`ingest_messages_total`).

Файлы в `mqtt-samples/` — только для ручной проверки, это не часть контракта: эталон — `contracts/mqtt/examples`.

## Эмулятор IoT-шлюза

Python-эмулятор из `tools/emulator` подключён к Compose через профиль `emulator`. Профиль не
активируется обычной командой `docker compose up`, поэтому базовая инфраструктура не требует
настройки эмулятора. Внутри Compose-сети эмулятор всегда подключается к `rabbitmq:1883`.

Перед запуском скопируйте локальные значения окружения и убедитесь, что
`SENSO_EMULATOR_PASSWORD` задан в `deploy/.env`:

```powershell
Copy-Item deploy/.env.example deploy/.env
docker compose -f deploy/docker-compose.yml up -d --wait rabbitmq
docker compose -f deploy/docker-compose.yml --profile emulator build emulator
```

Сценарий по умолчанию — `normal --once`:

```powershell
docker compose -f deploy/docker-compose.yml --profile emulator run --rm --no-deps emulator normal --once
```

Остальные реализованные сценарии запускаются так:

```powershell
docker compose -f deploy/docker-compose.yml --profile emulator run --rm --no-deps emulator offline
docker compose -f deploy/docker-compose.yml --profile emulator run --rm --no-deps emulator anomaly
docker compose -f deploy/docker-compose.yml --profile emulator run --rm --no-deps emulator discover
docker compose -f deploy/docker-compose.yml --profile emulator run --rm --no-deps emulator duplicate
```

`--no-deps` допустим после явного ожидания RabbitMQ через `up -d --wait rabbitmq`. Пароль не
передаётся через аргументы CLI и не включается в образ. Если пароль отсутствует, обычный запуск
PostgreSQL и RabbitMQ не ломается, но эмулятор завершает работу с кодом `2`. Подробные параметры,
последовательности сообщений и ограничения сценариев описаны в `tools/emulator/README.md`.

Для проверки доставки без чтения и очистки общей `q.ingest.raw` используйте изолированный
RabbitMQ и интеграционные тесты эмулятора. Рост `messages_ready` в общей очереди сам по себе не
доказывает принадлежность сообщений конкретному запуску.

Что объявлено в `rabbitmq/definitions.json`, а что кодом:

- здесь: пользователи, vhost `senso`, обменники `senso.*`, входная очередь `q.ingest.raw` и DLQ-очереди.
  Входная очередь нужна до старта `ingest`, иначе сообщения шлюзов без очереди теряются;
- в коде (`Declarables`): очереди модулей `core` (`q.telemetry.writer` и др.). Если `ingest` тоже
  объявляет `q.ingest.raw`, аргументы должны совпадать с этим файлом, иначе `PRECONDITION_FAILED`.

**Аргументы существующей очереди не меняются.** Если в `definitions.json` поменялись аргументы очереди
(например, у `q.ingest.raw` появился `x-delivery-limit: 5`), брокер со старым volume оставит старую очередь, а
`ingest` упадёт на объявлении с `PRECONDITION_FAILED`. Пересоздай данные брокера (Postgres не трогается):

```bash
docker compose -f deploy/docker-compose.yml rm -sf rabbitmq
docker volume rm senso_rabbit-data
docker compose -f deploy/docker-compose.yml up -d rabbitmq
```

или сбрось всё целиком: `docker compose -f deploy/docker-compose.yml down -v` (удалит и данные Postgres).

Новый пароль пользователя RabbitMQ: `python deploy/rabbitmq/hash_password.py <пароль>`, хэш в
`definitions.json`, пароль в `.env.example`.

Сбросить всё: `docker compose -f deploy/docker-compose.yml down -v`.
