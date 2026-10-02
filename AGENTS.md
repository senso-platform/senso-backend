# AGENTS.md — правила работы в senso-backend

Этот файл читают AI-агенты (Claude Code, Codex, Cursor и др.) и новые участники команды перед любой задачей. Если правило здесь противоречит твоей привычке, прав этот файл. Если правило противоречит задаче, остановись и спроси.

Первичные документы (читать в таком порядке):

1. `docs/adr/ADR-0001-v2-backend-architecture.md` — архитектура и почему она такая.
2. `docs/bootstrap/INIT-PLAN.md` — план инициализации (пока репозиторий не собран, работай строго по нему).
3. `README.md` — как запускать и где что лежит.
4. `contracts/` — контракты. Код подстраивается под контракт, а не наоборот.
5. `docs/dev/ONBOARDING.md` — как поднять проект с нуля; `docs/modules/README.md` — анатомия модуля; `docs/dev/TROUBLESHOOTING.md` — известные грабли версий (смотри до того, как гуглить).

## 1. Цель проекта сейчас

MVP до января 2027: вход, шлюзы и устройства, телеметрия и графики, алерты от IDS шлюза, SSE. **Не делай** без явной задачи: блокчейн, оплату, маркетплейс, команды устройствам, проверку подписи (только флаг и заглушка), Redis, Kubernetes, микросервисы сверх `core` и `ingest`.

## 2. Команды

```bash
./mvnw verify                      # всё: сборка, тесты, Testcontainers, ArchUnit, Modulith verify (нужен Docker)
./mvnw -pl core-app -am test       # только core
./mvnw spotless:apply              # форматирование (запускать перед коммитом и перед verify: spotless:check входит в verify)
docker compose -f deploy/docker-compose.yml up -d                  # postgres + rabbitmq
docker compose -f deploy/docker-compose.yml --profile app up -d    # + приложения
./mvnw -pl core-app -am spring-boot:run -Dspring-boot.run.profiles=local   # без профиля core-app не стартует
./mvnw -pl core-app test -Dtest=ArchitectureTest -Dmodulith.docs=true    # + документация модулей в target/spring-modulith-docs
```

Задача считается выполненной только если `./mvnw verify` зелёный.

## 3. Архитектурные правила (проверяются в CI, нарушать нельзя)

1. **Модули `core`**: `identity`, `devices`, `telemetry`, `alerts`, `realtime`. Корневой пакет `dev.senso.core.<module>`.
2. Другие модули видят только подпакет `<module>.api` (`@NamedInterface("api")`). Всё остальное в `<module>.internal`.
3. Разрешённые зависимости (в `package-info.java`, `@ApplicationModule(allowedDependencies = …)`):
   - `identity` → ничего;
   - `devices` → `identity::api`;
   - `telemetry` → `devices::api`;
   - `alerts` → `devices::api`;
   - `realtime` → `devices::api`, `alerts::api`.
   Плюс каждому модулю разрешён `shared` (инфраструктура: роли, веб, планировщики, позже security). `shared` — модуль
   типа OPEN и сам ни от кого не зависит; доменной логики в нём нет.
   Новую зависимость добавлять только после согласования с TL. Циклы запрещены.
4. **Нет общих JPA-сущностей между модулями.** Нет `@ManyToOne` на сущность другого модуля. Ссылка на чужой объект это `UUID`.
5. **Схема БД на модуль** (`identity`, `devices`, `telemetry`, `alerts`, `platform`). Нет внешних ключей и join между схемами. Каждая сущность указывает `@Table(schema = "<module>")`.
6. Межмодульные события: records в `<module>.api.events`, имя с версией (`AlertRaisedV1`), в полях только примитивы, `UUID`, `Instant`, строки.
7. События наружу (в RabbitMQ) только через Spring Modulith event publication registry (outbox) и `@Externalized`. Прямой `RabbitTemplate`/`AmqpTemplate` разрешён только в `<module>.internal.messaging` (адаптеры брокера): это живая телеметрия `senso.live` (допустима потеря, публикуется после коммита). Сервисы и домен в брокер напрямую не пишут.
8. `ingest` **не ходит в БД** и не зависит от `core`. Общее только `platform-kernel` и `messaging-contracts`.
9. Код роли `api` помечается `@ApiRole`, роли `worker` — `@WorkerRole` (мета-аннотации над `@Profile`). Контроллеры и SSE только в `api`. У каждого класса с `@RabbitListener`/`@Scheduled` есть роль: очереди модулей и фоновые задачи — `worker`; в `api` только `realtime` (анонимная очередь `senso.notifications`, heartbeat SSE). Задача, которая не должна идти параллельно на нескольких инстансах, — `@SchedulerLock(name = "<module>.<task>")`. Без профиля `api`/`worker`/`local` приложение не стартует (`RoleProfilesGuard`); в тестах — `@ActiveProfiles`.
10. Проверка владельца выполняется в сервисе модуля (не только в контроллере). Чужой объект → `NotFoundException` → `404`.

## 4. Код

- Java 21. DTO, команды, события, ответы — `record`. Lombok **не используется**.
- Маппинг сущность ↔ DTO через MapStruct (`componentModel = "spring"`), интерфейс маппера в `internal`.
- Идентификаторы: `UUID` v7, генерируются в приложении через `Ids.newId()` из `platform-kernel`. Не использовать `@GeneratedValue` с `IDENTITY`/`SEQUENCE` для доменных сущностей.
- Время: только `Instant` (UTC). Текущее время брать из инжектированного `Clock` (`platform-kernel`), не `Instant.now()`. В БД `timestamptz`.
- Контроллеры реализуют интерфейсы, сгенерированные из `contracts/openapi/senso-api.v1.yaml` (пакеты `dev.senso.openapi.api` и `dev.senso.openapi.model`, лежат в `core-app/target/generated-sources`). Генерированный код не редактировать. Нужно изменить API — сначала контракт.
- Списки: `PageRequestParams` → запрос с `LIMIT params.fetchSize()` (на одну строку больше) → `CursorPage.of(rows, params.limit(), …)`. Ключ курсора совпадает с `ORDER BY`.
- Ошибки: бросать исключения из `platform-kernel` (`NotFoundException`, `ConflictException`, `ValidationException`, `ForbiddenException`). Преобразование в RFC 9457 Problem Details делает один глобальный `ProblemDetailsAdvice` в `core.shared.web`. Свои `@ExceptionHandler`/`@ControllerAdvice` в модулях не заводить.
- Транзакции: `@Transactional` на методах сервисов в `internal`, не на контроллерах и не на репозиториях.
- Горячий путь записи телеметрии: `JdbcTemplate.batchUpdate` с `ON CONFLICT DO NOTHING`, не JPA.
- Логи: SLF4J, структурированные поля через MDC (`traceId`, `gatewayId`, `deviceId`, `messageId`, `userId`). Никогда не логировать пароли, токены, refresh-cookie, MQTT-пароли, подписи.
- Конфигурация: `@ConfigurationProperties` records с префиксом `senso.<module>`, не `@Value` по коду.
- Не добавлять новые зависимости в `pom.xml` без необходимости. Если добавил — версия через `<properties>`, объясни зачем в описании PR.

## 5. База данных и Flyway

- Миграции: `core-app/src/main/resources/db/migration/<module>/V<yyyyMMddHHmm>__<module>_<описание>.sql` (версия-таймстамп исключает конфликты между разработчиками).
- Слитую в `main` миграцию не редактировать никогда. Нужна правка — новая миграция.
- Имена в SQL всегда со схемой (`devices.gateways`, не `gateways`): схема по умолчанию у Flyway — `platform`.
- `CREATE EXTENSION` только в `platform/V202610010900__platform_extensions.sql` (новое расширение — новая миграция `platform`).
- Миграции из параллельных веток могут влиться не по порядку версий: `spring.flyway.out-of-order: true` это допускает. Поэтому миграция не должна зависеть от миграции другой ветки, которой ещё нет в `develop`.
- Миграции с `create_hypertable` можно в транзакции. Создание continuous aggregate и политики Timescale — отдельным файлом с первой строкой `-- flyway:executeInTransaction=false`.
- Миграции выполняет только роль `api` (`spring.flyway.enabled=true` в профиле `api`, `false` в `worker`).
- Индексы под каждый новый фильтр списка. Для списков алертов keyset-пагинация по `(last_occurred_at, id)`.

## 6. Сообщения (RabbitMQ)

- Имена обменников, очередей и ключей только из констант `messaging-contracts` (`Exchanges`, `Queues`, `RoutingKeys`). Строки-литералы в коде запрещены.
- Топология объявляется кодом (`Declarables`) в том приложении, которое **потребляет** очередь. Все очереди durable, quorum, с `x-dead-letter-exchange = senso.dlq`.
- Листенер подтверждает сообщение только после коммита транзакции. Ошибки: 3 повтора с экспоненциальной паузой, затем reject → DLQ.
- Каждый приёмник идемпотентен. Дубль сообщения не должен ничего сломать — это покрывается тестом.
- `traceparent`, `messageId`, `gatewayId` передаются в AMQP-заголовках.

## 7. Безопасность

- Все `/api/v1/**` требуют JWT, кроме `/auth/register`, `/auth/login`, `/auth/refresh`, `/.well-known/jwks.json`, `/internal/mqtt-auth/**` (последний закрыт сетевым уровнем, не публикуется через nginx).
- JWT: RS256, access 15 минут. Refresh: случайная строка, в БД только SHA-256-хэш, ротация на каждом использовании, повторное использование отзывает семейство.
- Пароли пользователей и MQTT-пароли шлюзов: BCrypt (cost 12).
- Любой endpoint, возвращающий данные пользователя, получает негативный тест «пользователь B не видит объект пользователя A» (утилита `core-app/src/test/java/dev/senso/core/support/OwnershipTestSupport.java`: чужой объект → `NotFoundException`).
- Текст от шлюза (`message`, `details`, имена) хранится и отдаётся как есть, без интерпретации как HTML/SQL.

## 8. Тесты

- Unit: `*Test.java` (или `*Tests.java`), без Spring-контекста где возможно. Веб-слой — standalone MockMvc (образец `ProblemDetailsAdviceTest`).
- Интеграционные: `*IT.java`, Testcontainers (`timescale/timescaledb:<pinned>-pg16`, `rabbitmq:<pinned>-management`), общий базовый класс `IntegrationTestBase` с переиспользуемыми контейнерами.
- Модульные: `@ApplicationModuleTest` для изолированной проверки модуля.
- Для асинхронщины: Awaitility, не `Thread.sleep`.
- `ArchitectureTest` (`ApplicationModules.of(CoreApplication.class).verify()` + ArchUnit-правила из раздела 3) не отключать и не ослаблять. Новое правило добавляется только вместе с фикстурой-нарушителем (`RuleCase`) в `src/test/java/dev/senso/archrules/violators` — иначе `ArchRuleFixturesTest` красный.
- Контекст поднимается в каждой роли: `CoreApplicationIT` (api), `CoreWorkerContextIT`, `CoreLocalContextIT`. Новый бин, нужный обеим ролям, не помечается ролью.
- Новый код без тестов не принимается.

## 9. Git и PR

- Ветка `feature/SCRUM-<n>-<slug>`, коммиты по Conventional Commits.
- Один PR — одна задача, по возможности до ~400 строк изменений без учёта сгенерированного.
- В описании PR: что сделано, как проверить, какие контракты/миграции затронуты.
- Не коммитить: `deploy/.env`, ключи, `target/`, `.idea/`, дампы БД.

## 10. Когда остановиться и спросить

- Нужно изменить контракт (`contracts/`) или добавить межмодульную зависимость.
- Нужна новая инфраструктура (Redis, новый брокер, новый сервис).
- Задача требует ослабить проверку безопасности или архитектурный тест.
- Непонятно, что должно происходить с данными при сбое (ретраи, потеря, дубли).
- ADR и задача противоречат друг другу.
