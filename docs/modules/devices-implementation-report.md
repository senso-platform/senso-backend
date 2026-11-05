# Отчёт: основа модуля devices

## 1. Архитектура и объём

SensoBazaar — модульный монолит `core-app` и отдельный `ingest-app`. Реализована независимая основа
`devices`: собственная схема БД, внутренние сервисы и публичный именованный интерфейс `api`.
Разрешённая доменная зависимость — `identity::api`; общая инфраструктура — `shared`.
Новые библиотеки, инфраструктура и межмодульные зависимости не добавлялись.

REST и брокерный приём пока заблокированы отсутствующими контрактами/готовым ingest pipeline.
Контракты и архитектурные проверки не менялись. Полное описание поведения:
[документация модуля](devices.md). Упомянутый в правилах `docs/bootstrap/INIT-PLAN.md` в checkout отсутствует.

## 2. Реализация и API

- `GatewayService`: создание шлюза, выдача случайного MQTT-пароля один раз, получение, переименование,
  список владельца. В БД только BCrypt(12)-хэш; `RegisteredGateway.toString()` скрывает пароль.
- `DeviceService`: получение, переименование, список, approve/reject/disable/enable с проверкой владельца.
  Чужой объект даёт `NotFoundException`. Списки используют keyset `(created_at DESC, id DESC)`,
  `PageRequestParams.fetchSize()` и `CursorPage.of`.
- `DeviceQueries`: публичные `resolve(gatewayExternalId, deviceExternalId)`, `find(ownerId, deviceId)`,
  `list(ownerId, gatewayId, params)`. Worker lookup `resolve` возвращает только `ACTIVE`.
- `DeviceDiscoveryService`: discovery, полный inventory, статусы шлюза; принимает существующие
  проверенные payload records. Это сервис приложения, без MQTT/AMQP-listener.
- `DeviceMapper`: Spring MapStruct для DTO; JPA-репозитории, новая миграция
  `V202611051200__devices_registry.sql`, метрика `devices.observations`, логи с MDC без секретов.

Новых HTTP endpoint нет: текущий OpenAPI не содержит REST устройств и шлюзов. Доверенный `ownerId`
внутренних методов будущий HTTP-адаптер обязан получать из JWT, а не из тела запроса.

## 3. Доменная модель и надёжность

`Gateway` хранит UUIDv7, владельца как UUID, внешний MQTT-id, имя, хэш пароля, состояние
`UNKNOWN / ONLINE / DEGRADED / OFFLINE`, bootId и временные отметки.
`Device` ссылается на шлюз UUID, хранит внешний id, тип, имя, capabilities/metadata JSONB,
независимую связность и аудит подтверждения. `DeviceCapability` — value record, отдельной сущности нет.

Уникальность датчика — `(gateway_id, external_id)`; одинаковый внешний id на разных шлюзах допустим.
FK только внутри `devices`, связей JPA с `identity` и межсхемных join нет.
Жизненный цикл: `PENDING → ACTIVE`, `PENDING → REJECTED`, `ACTIVE → DISABLED`,
`DISABLED → ACTIVE`; новое обнаружение может вернуть `REJECTED → PENDING`.
Повтор доставки прежнего discovery этого не делает. Повтор пользовательской команды идемпотентен.

Изменения сериализуются блокировкой шлюза. Не более 50 PENDING на шлюз.
`processed_messages` обеспечивает дедупликацию discovery/inventory в той же транзакции;
ошибка откатывает и изменения, и отметку обработки. Inventory отключает отсутствующие устройства,
сохраняя metadata; запоздалые снимки и discovery не перезаписывают более новые наблюдения.
GATEWAY_STATUS не дедуплицируется по messageId: статический Last Will применяется только
к актуальному bootId и не отвергается из-за заранее установленного sentAt. `DEGRADED`, доставленный раньше
более старого `ONLINE`, сохраняется; поздний `ONLINE` подтверждает сессию для Last Will без отката состояния.

## 4. Контракты C++

Сабмодуль `contracts/` доступен, использован commit `6269c24`. README называет его источником истины,
пометки draft нет; факт согласования с C++-командой локальными файлами не подтверждается.

Использованы MQTT v1 envelope и схемы/records `DEVICE_DISCOVERED`, `DEVICE_INVENTORY`,
`GATEWAY_STATUS`, включая внешний deviceId, capabilities, metadata и bootId.
Форматы, DTO и сабмодуль не менялись. `TELEMETRY` и `SECURITY_EVENT` остаются ответственностью
других модулей. Каталог типов/единиц и интервал измерений требуют дальнейшего согласования.

## 5. Взаимодействие модулей

Для проверки существования владельца шлюза добавлена реализация уже объявленного `IdentityApi`.
Это единственное технически необходимое изменение вне devices: три новых файла —
`IdentityApiAdapter`, MapStruct `IdentityUserMapper` и `IdentityApiAdapterTest`.
Существующая логика identity не менялась.

`telemetry`, `alerts`, `realtime`, `ingest-app`, `messaging-contracts` и `contracts/` не изменялись.
Будущий telemetry использует ACTIVE-only resolve; сейчас devices не записывает измерения.
Для ingest уже существуют `ValidatedMessageV1` и константы очередей/ключей, но рабочего
приёма, валидации и публикации в checkout нет. Временный транспорт и публикация в брокер не добавлялись.

## 6. Проверки

Добавлены unit-тесты домена, сервисов, MapStruct, владения, курсоров, статусов и защиты секретов,
а также `DevicesIT` на `@ApplicationModuleTest(DIRECT_DEPENDENCIES)` и общем `IntegrationTestBase`.
IT проверяет реальные PostgreSQL/TimescaleDB, JSONB/миграцию, discovery на разных шлюзах,
подтверждение, чужие объекты, inventory, Last Will, дубли, откат дедупликации, лимит и конкуренцию.
Общая тестовая инфраструктура также поднимает RabbitMQ; DevicesIT вызывает сервисы напрямую,
доставка сообщений AMQP в этом PR не проверяется.

Финальные `./mvnw spotless:apply` и `./mvnw verify` завершились **BUILD SUCCESS** на Java 21.
Проверка verify завершена 5 ноября 2026 в 09:52:51 по Москве.

| Проверка | Найдено тестов | Ошибки / падения | Пропуски |
| --- | ---: | ---: | ---: |
| platform-kernel unit | 11 | 0 | 0 |
| messaging-contracts | 2 | 0 | 0 |
| core-app unit, архитектура | 71 | 0 | 1 |
| core-app integration | 16 | 0 | 0 |
| **Всего** | **100** | **0** | **1** |

Из 16 интеграционных тестов 9 — новые сценарии DevicesIT. Итого 99 тестов выполнены успешно,
один необязательный тест генерации документации пропущен штатным условием.
Логи: `/tmp/senso-devices-spotless.log`, `/tmp/senso-devices-verify.log`.

Тесты и архитектурные правила не отключались и не ослаблялись. Существующий необязательный тест
генерации документации в `ArchitectureTest` запускается только с `-Dmodulith.docs=true`;
его стандартный skipped не означает пропуск проверки границ модулей.

## 7. Полный список изменённых и новых файлов

Список получен из `git diff --name-only` и `git ls-files --others --exclude-standard`,
включает этот отчёт. Сгенерированные файлы `target/` в список не входят.

- [`core-app/src/main/java/dev/senso/core/devices/api/Connectivity.java`](../../core-app/src/main/java/dev/senso/core/devices/api/Connectivity.java)
- [`core-app/src/main/java/dev/senso/core/devices/api/DeviceCapability.java`](../../core-app/src/main/java/dev/senso/core/devices/api/DeviceCapability.java)
- [`core-app/src/main/java/dev/senso/core/devices/api/DeviceQueries.java`](../../core-app/src/main/java/dev/senso/core/devices/api/DeviceQueries.java)
- [`core-app/src/main/java/dev/senso/core/devices/api/DeviceStatus.java`](../../core-app/src/main/java/dev/senso/core/devices/api/DeviceStatus.java)
- [`core-app/src/main/java/dev/senso/core/devices/api/DeviceView.java`](../../core-app/src/main/java/dev/senso/core/devices/api/DeviceView.java)
- [`core-app/src/main/java/dev/senso/core/devices/api/GatewayState.java`](../../core-app/src/main/java/dev/senso/core/devices/api/GatewayState.java)
- [`core-app/src/main/java/dev/senso/core/devices/api/GatewayView.java`](../../core-app/src/main/java/dev/senso/core/devices/api/GatewayView.java)
- [`core-app/src/main/java/dev/senso/core/devices/internal/domain/Device.java`](../../core-app/src/main/java/dev/senso/core/devices/internal/domain/Device.java)
- [`core-app/src/main/java/dev/senso/core/devices/internal/domain/Gateway.java`](../../core-app/src/main/java/dev/senso/core/devices/internal/domain/Gateway.java)
- [`core-app/src/main/java/dev/senso/core/devices/internal/mapping/DeviceMapper.java`](../../core-app/src/main/java/dev/senso/core/devices/internal/mapping/DeviceMapper.java)
- [`core-app/src/main/java/dev/senso/core/devices/internal/repository/DeviceRepository.java`](../../core-app/src/main/java/dev/senso/core/devices/internal/repository/DeviceRepository.java)
- [`core-app/src/main/java/dev/senso/core/devices/internal/repository/GatewayRepository.java`](../../core-app/src/main/java/dev/senso/core/devices/internal/repository/GatewayRepository.java)
- [`core-app/src/main/java/dev/senso/core/devices/internal/service/DeviceDiscoveryService.java`](../../core-app/src/main/java/dev/senso/core/devices/internal/service/DeviceDiscoveryService.java)
- [`core-app/src/main/java/dev/senso/core/devices/internal/service/DeviceService.java`](../../core-app/src/main/java/dev/senso/core/devices/internal/service/DeviceService.java)
- [`core-app/src/main/java/dev/senso/core/devices/internal/service/GatewayService.java`](../../core-app/src/main/java/dev/senso/core/devices/internal/service/GatewayService.java)
- [`core-app/src/main/java/dev/senso/core/devices/internal/service/RegisteredGateway.java`](../../core-app/src/main/java/dev/senso/core/devices/internal/service/RegisteredGateway.java)
- [`core-app/src/main/java/dev/senso/core/identity/internal/service/IdentityApiAdapter.java`](../../core-app/src/main/java/dev/senso/core/identity/internal/service/IdentityApiAdapter.java)
- [`core-app/src/main/java/dev/senso/core/identity/internal/service/IdentityUserMapper.java`](../../core-app/src/main/java/dev/senso/core/identity/internal/service/IdentityUserMapper.java)
- [`core-app/src/main/resources/db/migration/devices/V202611051200__devices_registry.sql`](../../core-app/src/main/resources/db/migration/devices/V202611051200__devices_registry.sql)
- [`core-app/src/test/java/dev/senso/core/devices/DevicesIT.java`](../../core-app/src/test/java/dev/senso/core/devices/DevicesIT.java)
- [`core-app/src/test/java/dev/senso/core/devices/internal/domain/DeviceTest.java`](../../core-app/src/test/java/dev/senso/core/devices/internal/domain/DeviceTest.java)
- [`core-app/src/test/java/dev/senso/core/devices/internal/domain/GatewayTest.java`](../../core-app/src/test/java/dev/senso/core/devices/internal/domain/GatewayTest.java)
- [`core-app/src/test/java/dev/senso/core/devices/internal/service/DeviceServiceTest.java`](../../core-app/src/test/java/dev/senso/core/devices/internal/service/DeviceServiceTest.java)
- [`core-app/src/test/java/dev/senso/core/devices/internal/service/GatewayServiceTest.java`](../../core-app/src/test/java/dev/senso/core/devices/internal/service/GatewayServiceTest.java)
- [`core-app/src/test/java/dev/senso/core/identity/internal/service/IdentityApiAdapterTest.java`](../../core-app/src/test/java/dev/senso/core/identity/internal/service/IdentityApiAdapterTest.java)
- [`docs/modules/devices-implementation-report.md`](../../docs/modules/devices-implementation-report.md)
- [`docs/modules/devices.md`](../../docs/modules/devices.md)

## 8. Следующий PR и ограничения

1. Согласовать REST шлюзов/устройств и MQTT auth в senso-contracts, добавить сгенерированные интерфейсы,
   JWT/current-user wiring, `@ApiRole`-контроллеры и негативные HTTP-тесты.
2. Завершить ingest pipeline и подключить `ValidatedMessageV1` через `q.devices.inbound`:
   topology, заголовки, worker-role, три повтора, DLQ, ack после коммита; добавить сквозной тест.
3. Согласовать уведомления realtime и подключить их через Spring Modulith outbox.
4. Реализовать worker-пересчёт связности по таймауту/телеметрии под ShedLock после определения периода
   измерений. Сейчас связность отражает discovery/inventory и явные статусы шлюза.
5. Определить срок хранения/очистку `devices.processed_messages`; реализовать ротацию и отзыв
   MQTT-паролей вместе с готовым broker auth backend. Метрика обработки не является транзакционным аудитом.

Коммиты, push и PR не создавались.

