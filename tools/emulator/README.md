# Эмулятор IoT-шлюза Senso

Утилита на Python 3.12 эмулирует один IoT-шлюз и публикует MQTT-сообщения,
соответствующие JSON Schema из `../../contracts/mqtt`. Для MQTT-транспорта используется
Paho MQTT, а для UUID v7 — пакет `uuid6`. Реализованы ограниченные сценарии
`normal`, `offline`, `anomaly`, `discover` и `duplicate`.

Эмулятор работает по MQTT 3.1.1 и дожидается PUBACK после каждой публикации с QoS 1.
Статусы `GATEWAY_STATUS` публикуются в retained-режиме в топик
`senso/v1/gw/{gatewayId}/status`. События `DEVICE_INVENTORY`, `DEVICE_DISCOVERED` и
`SECURITY_EVENT` публикуются без retain в `senso/v1/gw/{gatewayId}/events`, а `TELEMETRY` —
без retain в `senso/v1/gw/{gatewayId}/telemetry`.

Каждая MQTT-сессия использует один `bootId`. Каждое новое логическое сообщение
получает UUID v7 в `messageId`. В обычных сценариях `seq` монотонно увеличивается.
Исключение — вторая публикация в `duplicate`, которая намеренно повторяет то же
логическое сообщение без изменения `seq` и `messageId`.

До MQTT CONNECT регистрируется retained Last Will со статусом OFFLINE. Last Will имеет тот же
`bootId`, собственный UUID v7 `messageId` и не содержит `seq`, так как брокер может
опубликовать его асинхронно в любой момент сессии.

Если ONLINE или явный OFFLINE не подтверждён, эмулятор не отправляет MQTT DISCONNECT,
который мог бы подавить Last Will. Вместо этого он останавливает сетевой цикл Paho и
закрывает сокет, полученный через публичный API `Client.socket()`, чтобы RabbitMQ мог
опубликовать Last Will. Автоматическое повторное подключение для этих конечных сценариев
отключено.

## Настройка локального окружения

Выполните команды в Windows PowerShell из каталога `tools/emulator`:

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -e ".[dev]"
```

Для Linux/macOS:

```bash
python3.12 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -e '.[dev]'
```

Виртуальное окружение `.venv` является локальным состоянием разработчика и не должно
попадать в Git.

## Запуск `normal`

Сценарий `normal` имитирует штатную работу шлюза. Последовательность:

1. retained `GATEWAY_STATUS: ONLINE`;
2. `DEVICE_INVENTORY` с устройствами из начального снимка;
3. по одному `DEVICE_DISCOVERED` для каждого устройства, обнаруженного после снимка;
4. один или несколько пакетов `TELEMETRY`;
5. retained `GATEWAY_STATUS: OFFLINE`;
6. MQTT DISCONNECT.

Множества инвентаризации и обнаружения намеренно не пересекаются. `DEVICE_INVENTORY` — это полный
начальный снимок по текущему контракту, а `DEVICE_DISCOVERED` — последующая дельта.
Сообщение об обнаружении не дублируется для устройств, уже вошедших в инвентаризацию.

Запустите существующий RabbitMQ из корня репозитория:

```powershell
docker compose -f deploy/docker-compose.yml up -d rabbitmq
```

Задайте пароль через переменную окружения. Не передавайте его через CLI:

```powershell
$env:SENSO_EMULATOR_PASSWORD = 'emulator-dev-pass'
python -m senso_emulator normal --once
```

С настройками по умолчанию `normal --once` публикует два начальных устройства в
inventory, затем обнаруживает одно новое устройство, публикует один пакет телеметрии
для всех трёх устройств и штатно завершает сессию. В `q.ingest.raw` поступает пять
сообщений в протокольном порядке.

Пример ограниченного периодического запуска:

```powershell
python -m senso_emulator normal `
  --devices 4 `
  --discovered-devices 1 `
  --interval 2 `
  --duration 30 `
  --seed 42
```

`TELEMETRY` публикуется сразу, а затем один раз за интервал, пока заданное временное окно
допускает ещё один пакет. Количество пакетов равно `ceil(duration / interval)`. Ожидание
PUBACK может увеличить фактическое время работы по сравнению с указанной длительностью.

Значения по умолчанию совпадают с локальной конфигурацией брокера:

- адрес: `localhost` (`SENSO_MQTT_HOST`);
- порт: `1883` (`SENSO_MQTT_PORT`);
- шлюз и имя пользователя MQTT: `gw-emulator-01` (`SENSO_EMULATOR_GATEWAY_ID`);
- keepalive: `30` секунд (`SENSO_MQTT_KEEPALIVE`);
- тайм-аут подключения и ожидания PUBACK: `10` секунд (`SENSO_MQTT_TIMEOUT`);
- начальные устройства: `2` (`SENSO_EMULATOR_DEVICE_COUNT`);
- устройства, обнаруженные после inventory: `1` (`SENSO_EMULATOR_DISCOVERED_COUNT`);
- интервал телеметрии: `1` секунда (`SENSO_EMULATOR_INTERVAL`);
- длительность: `10` секунд (`SENSO_EMULATOR_DURATION`);
- начальное значение детерминированной генерации телеметрии: `0` (`SENSO_EMULATOR_SEED`).

Для всех несекретных параметров есть CLI-опции. `--once` игнорирует `--duration` и публикует
ровно один пакет телеметрии без интервальной задержки. Пароль намеренно не имеет
CLI-опции. Секреты не записываются в лог и не включаются в текст исключений.

При успешной работе в логе отображается PUBACK для каждого статуса, события и пакета
телеметрии.

## Запуск в Docker Compose

Образ эмулятора использует Python 3.12 и запускается от непривилегированного пользователя.
Сервис входит в профиль `emulator`, поэтому обычная команда запуска инфраструктуры не собирает
и не запускает его. В контейнере MQTT-брокер доступен по имени Compose-сервиса
`rabbitmq` на внутреннем порту `1883`; опубликованный на хост порт для этого не используется.

Из корня репозитория создайте локальный файл окружения и поднимите RabbitMQ:

```powershell
Copy-Item deploy/.env.example deploy/.env
docker compose -f deploy/docker-compose.yml up -d --wait rabbitmq
docker compose -f deploy/docker-compose.yml --profile emulator build emulator
```

Пароль передаётся контейнеру только через `SENSO_EMULATOR_PASSWORD` из окружения Compose.
Он не является аргументом CLI и не записывается в Dockerfile или образ. Если переменная отсутствует
либо пуста, обычные команды Compose без профиля продолжают работать, но контейнер эмулятора
отклоняет запуск с кодом `2`.

После готовности RabbitMQ все пять сценариев можно запускать отдельными одноразовыми контейнерами:

```powershell
docker compose -f deploy/docker-compose.yml --profile emulator run --rm --no-deps emulator normal --once
docker compose -f deploy/docker-compose.yml --profile emulator run --rm --no-deps emulator offline
docker compose -f deploy/docker-compose.yml --profile emulator run --rm --no-deps emulator anomaly
docker compose -f deploy/docker-compose.yml --profile emulator run --rm --no-deps emulator discover
docker compose -f deploy/docker-compose.yml --profile emulator run --rm --no-deps emulator duplicate
```

`--no-deps` следует использовать только после успешного `up -d --wait rabbitmq`. Без него Compose
сам запустит зависимый RabbitMQ и дождётся его healthcheck. Команда
`docker compose -f deploy/docker-compose.yml --profile emulator up emulator` запускает сценарий
по умолчанию `normal --once`; для остальных сценариев предназначен `run --rm` с аргументами выше.

## Запуск `offline`

Сценарий `offline` проверяет MQTT Last Will. Последовательность:

1. регистрация retained Last Will `GATEWAY_STATUS: OFFLINE` до MQTT CONNECT;
2. подключение к RabbitMQ;
3. публикация retained `GATEWAY_STATUS: ONLINE` и ожидание PUBACK;
4. контролируемое закрытие TCP-соединения без MQTT DISCONNECT;
5. публикация зарегистрированного retained OFFLINE брокером RabbitMQ.

Эмулятор не публикует явный OFFLINE в этом сценарии.

```powershell
$env:SENSO_EMULATOR_PASSWORD = 'emulator-dev-pass'
python -m senso_emulator offline
```

ONLINE и Last Will используют один новый `bootId`; Last Will имеет собственный UUID v7
`messageId`. Сценарий завершается с кодом `0` после успешного контролируемого аварийного
завершения, с кодом `2` при ошибке конфигурации и с кодом `3` при ошибке MQTT или
аварийной очистки.

Проверить очередь можно из корня репозитория:

```powershell
docker compose -f deploy/docker-compose.yml exec rabbitmq `
  rabbitmqctl list_queues -p senso name messages_ready consumers
```

Интерфейс RabbitMQ Management также доступен по адресу <http://localhost:15672>.

## Запуск `anomaly`

Сценарий `anomaly` публикует одно корректное событие `SECURITY_EVENT` уровня шлюза.
Актуальный контракт разрешает не указывать `deviceId` для таких событий. Последовательность:

1. retained `GATEWAY_STATUS: ONLINE`;
2. `SECURITY_EVENT` с `severity=WARNING` и `code=GATEWAY_TRAFFIC_SPIKE`;
3. retained `GATEWAY_STATUS: OFFLINE`;
4. MQTT DISCONNECT.

```powershell
$env:SENSO_EMULATOR_PASSWORD = 'emulator-dev-pass'
python -m senso_emulator anomaly
```

## Запуск `discover`

Сценарий `discover` имитирует обнаружение одного нового устройства после подключения
шлюза. Последовательность:

1. retained `GATEWAY_STATUS: ONLINE` с числом исходных устройств;
2. `DEVICE_INVENTORY` с полным начальным снимком;
3. `DEVICE_DISCOVERED` для одного нового `deviceId`, отсутствующего в inventory;
4. retained `GATEWAY_STATUS: OFFLINE` с числом устройств, увеличенным на один;
5. MQTT DISCONNECT.

`--devices` задаёт число устройств в исходном снимке:

```powershell
$env:SENSO_EMULATOR_PASSWORD = 'emulator-dev-pass'
python -m senso_emulator discover --devices 2
```

## Запуск `duplicate`

Сценарий `duplicate` один раз создаёт и сериализует `SECURITY_EVENT`, а затем выполняет две
отдельные MQTT-публикации с одинаковыми топиком и байтами payload. В обеих доставках неизменны
`messageId`, `sentAt`, `bootId`, `seq` и payload. Для каждой публикации ожидается отдельный
PUBACK. Последовательность:

1. retained `GATEWAY_STATUS: ONLINE`;
2. первая публикация `SECURITY_EVENT`;
3. вторая публикация того же `SECURITY_EVENT` без повторной генерации;
4. retained `GATEWAY_STATUS: OFFLINE`;
5. MQTT DISCONNECT.

```powershell
$env:SENSO_EMULATOR_PASSWORD = 'emulator-dev-pass'
python -m senso_emulator duplicate
```

Сценарии `anomaly`, `discover` и `duplicate` завершаются с кодом `0` после штатной сессии,
с кодом `2` при ошибке конфигурации и с кодом `3` при ошибке MQTT. Как и в остальных
сценариях, пароль берётся только из `SENSO_EMULATOR_PASSWORD`.

## Тестирование

Модульные тесты не требуют брокера и читают схемы непосредственно из рабочей копии сабмодуля
контрактов:

```powershell
python -m pytest -m "not integration"
python -m ruff format --check .
python -m ruff check .
```

Интеграционные тесты запускаются только явно и только с изолированным одноразовым
RabbitMQ. Тесты создают и удаляют собственную временную `q.ingest.raw`; не направляйте их
на общий Compose-брокер. Тест `normal --once` читает все сообщения и проверяет точные
`messageId`, содержимое, шлюз, `bootId`, монотонность `seq`, типы и порядок, а не только
изменение глубины очереди.

Тест Last Will дополнительно создаёт позднюю MQTT-подписку и требует от брокера доставить
ожидаемый retained OFFLINE с QoS 1. Затем тест запускает новую сессию `normal --once`, проверяет
новый `bootId` и замену retained-статуса на явный финальный OFFLINE из этой сессии.
Тесты отказываются запускаться, если `q.ingest.raw` уже существует, поэтому ошибочно заданный
флаг изоляции не приведёт к удалению или чтению общей очереди. Интеграционный тест событий
читает фактические доставки брокера для `anomaly`, `discover` и `duplicate` и сравнивает два
дублирующихся payload побайтно.

Для одноразового брокера, MQTT-плагин которого использует vhost `/`, задайте:

```powershell
$env:SENSO_RUN_MQTT_INTEGRATION = '1'
$env:SENSO_MQTT_INTEGRATION_ISOLATED = '1'
$env:SENSO_EMULATOR_PASSWORD = 'emulator-dev-pass'
$env:SENSO_RABBIT_ADMIN_USER = 'gw-emulator-01'
$env:SENSO_RABBIT_ADMIN_PASSWORD = 'emulator-dev-pass'
$env:SENSO_RABBIT_VHOST = '/'
python -m pytest -m integration
```

Дополнительно можно задать `SENSO_RABBIT_MANAGEMENT_URL` (по умолчанию
`http://localhost:15672`) и обычные MQTT-настройки, перечисленные выше. Без обоих явных флагов
явного разрешения обоими флагами интеграционные тесты пропускаются; пропуск не является
доказательством работоспособности.

## Текущие ограничения

- Эмулятор работает с одним шлюзом и реализует ограниченные сценарии `normal`, `offline`,
  `anomaly`, `discover` и `duplicate`.
- Сценарии `malformed`, `out-of-order` и `flood` пока не реализованы.
- Используется MQTT 3.1.1 без TLS, что соответствует локальной Compose-конфигурации.
- JSON Schema не включаются в Python-пакет и не копируются: тестам требуется репозиторий
  с рабочей копией сабмодуля `contracts`.
- `messageId` имеет формат UUID v7 в соответствии с описанием контракта.
- Текущая схема `bootId` требует только строку длиной от 8 до 64 символов, соответствующую
  `[A-Za-z0-9_-]+`. Реализация использует новый UUID v4 в качестве `bootId`, но контракт не требует
  для него UUID v7.
- Обработка в backend ingest не входит в текущий этап; доставка завершается в `q.ingest.raw`.
