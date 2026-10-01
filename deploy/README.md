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

Сообщение должно появиться в очереди `q.ingest.raw` (RabbitMQ UI → Queues). Публикация в чужой топик
(`senso/v1/gw/other/telemetry`) должна отклоняться.

Что объявлено в `rabbitmq/definitions.json`, а что кодом:

- здесь: пользователи, vhost `senso`, обменники `senso.*`, входная очередь `q.ingest.raw` и DLQ-очереди.
  Входная очередь нужна до старта `ingest`, иначе сообщения шлюзов без очереди теряются;
- в коде (`Declarables`): очереди модулей `core` (`q.telemetry.writer` и др.). Если `ingest` тоже
  объявляет `q.ingest.raw`, аргументы должны совпадать с этим файлом, иначе `PRECONDITION_FAILED`.

Новый пароль пользователя RabbitMQ: `python deploy/rabbitmq/hash_password.py <пароль>`, хэш в
`definitions.json`, пароль в `.env.example`.

Сбросить всё: `docker compose -f deploy/docker-compose.yml down -v`.
