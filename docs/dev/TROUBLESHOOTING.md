# Подводные камни версий и окружения

Справочник «симптом → причина → фикс» для закреплённого стека (Boot 4.1, Modulith 2.1, Testcontainers 2,
TimescaleDB 2.30, RabbitMQ 4.3, openapi-generator 7.25). Прежде чем гуглить ошибку сборки или старта — проверь здесь.

Всё проверено на закреплённых версиях этого репозитория. Добавляй сюда новые грабли в том же формате.

### 1. Maven / реактор

| Симптом | Причина | Фикс |
| --- | --- | --- |
| IT молча не запускаются на `verify` | `maven-failsafe-plugin` был только в `pluginManagement` | добавить в `<build><plugins>` родителя (executions `integration-test`+`verify`) |
| Сборка падает на spotless после записи файлов | Write-инструмент на Windows выдаёт CRLF | `./mvnw -B -ntp spotless:apply` перед каждой `verify` |
| «BUILD SUCCESS», хотя тесты упали | конвейер `./mvnw ... \| tail -60` возвращает код `tail` | не пайпить mvn, либо грепать вывод по `BUILD FAILURE` |
| `spotless` не хочет форматировать | плагин требует `palantir-java-format` как dependency плагина | уже настроено в parent |

### 2. Spring Boot 4.1.1 (это главный источник сюрпризов)

- Стартеры переименованы: `spring-boot-starter-web` → **`spring-boot-starter-webmvc`**; тестовый — `spring-boot-starter-webmvc-test` (без него нет ни MockMvc, ни web-test поддержки).
- `spring-boot-starter-flyway` + отдельно `flyway-database-postgresql` (иначе Flyway не знает про postgres).
- **Jackson 3**: тип `tools.jackson.databind.ObjectMapper`, исключения `tools.jackson.core.JacksonException` (не checked). Аннотации остаются `com.fasterxml.jackson.annotation.*` (jackson-annotations 3.x не переехал) — `messaging-contracts` использует их как есть. `spring-boot-starter-webmvc` тянет `spring-boot-starter-jackson`, так что `ObjectMapper` доступен без дополнительных зависимостей.
- `TestRestTemplate` переехал в `org.springframework.boot.resttestclient` и **больше не автоконфигурируется**: нужна аннотация `@AutoConfigureTestRestTemplate`, а она требует артефакт `spring-boot-restclient` (в дереве зависимостей его нет →
  `NoClassDefFoundError: org/springframework/boot/restclient/RestTemplateBuilder` при старте контекста теста).
  Текущее решение в `CoreApplicationIT`: **не** использовать `TestRestTemplate`, а `@LocalServerPort` + `RestClient` (`org.springframework.web.client.RestClient`) — новых зависимостей не нужно. Если захотим TestRestTemplate — добавить test-зависимость `org.springframework.boot:spring-boot-restclient`.
- `spring.threads.virtual.enabled: true` работает как раньше.

### 3. Spring Modulith 2.1.1

- `@Modulithic` лежит в `org.springframework.modulith` (не в `.core`); `ApplicationModules` — в `org.springframework.modulith.core`.
- `allowedDependencies` принимает **короткие** имена модулей: `"identity::api"`, NOT FQN (`"dev.senso.core.identity"` → `No module found with name ...`).
- `sharedModules = "shared"` влияет **только** на `@ApplicationModuleTest` (модуль всегда поднимается в тесте). От проверки
  зависимостей он не освобождает: доступ к `shared.*` дают `@ApplicationModule(type = OPEN)` в `shared/package-info.java`
  и `"shared"` в `allowedDependencies` каждого модуля. Подпакеты модуля по умолчанию внутренние.
- Схема outbox по умолчанию V2 ; property `spring.modulith.events.jdbc.schema: platform`.
- Свойство `spring.modulith.events.republish-outstanding-events-on-restart` в 2.1.1 **не** использовано (не проверено в metadata) — не добавлять, пока не понадобится.
- `Documenter` (модуль `spring-modulith-docs`, test-scope) вызывается в отдельном тесте и обёрнут в try/catch — документация в `target/modulith-docs`, тест не должен ронять сборку.

### 4. Testcontainers 2.0.5 (импортируется Boot BOM)

Артефакты **переименованы** относительно 1.x — старое имя (`testcontainers`, `postgresql`, `rabbitmq`) не резолвится с версией из BOM:

```xml
org.testcontainers:testcontainers-junit-jupiter
org.testcontainers:testcontainers-postgresql
org.testcontainers:testcontainers-rabbitmq
```

Использование: `DockerImageName.parse("timescale/timescaledb:2.30.2-pg16").asCompatibleSubstituteFor("postgres")`,
`RabbitMQContainer("rabbitmq:4.3.6-management")`, `withReuse(true)` (для rabbit — `.withReuse()`).
В `IntegrationTestBase` виртуальный хост для тестов перебит на `/` (в контейнере vhost `senso` не создаётся).

### 5. TimescaleDB 2.30.2-pg16 + Flyway 11

| Симптом | Причина | Фикс |
| --- | --- | --- |
| `function add_refresh_policy(unknown, schedule_interval => interval) does not exist` | в 2.30 публичное имя — `add_continuous_aggregate_policy`; `add_refresh_policy` отсутствует (есть только внутренняя `_timescaledb_functions.policy_refresh_continuous_aggregate`) | использовать `add_continuous_aggregate_policy(view, start_offset => INTERVAL ..., end_offset => INTERVAL ..., schedule_interval => INTERVAL ...)`; `end_offset` обязателен |
| `ERROR 22023: policy refresh window too small` | окно `start_offset - end_offset` должно покрывать **≥ 2 бакета** | `_1m`: start `1 hour` / end `1 minute`; `_1h`: start `1 day` / end `1 hour` |
| `continuous aggregate view must include a valid time bucket function` при создании `_1h` поверх `_1m` | повторная агрегация `min()/max()` cagg-на-cagg запрещена (сообщение об ошибке вводило в заблуждение); `avg()`/`sum()` — разрешены | `_1h` строится из сырой `telemetry.measurements` |
| `create_hypertable` внутри транзакции Flyway | ок, не требует исключений | — |
| `CREATE MATERIALIZED VIEW ... (timescaledb.continuous)` / политики внутри транзакции | не выполняются вне транзакции | отдельный файл, **первая строка** `-- flyway:executeInTransaction=false` |
| `add_retention_policy` на cagg | поддерживается (проверено) | — |
| `type "citext" does not exist` в IT, хотя локально работает | контейнер Testcontainers не получает `deploy/postgres/init/`, расширения там не созданы | все расширения — в `platform/V202610010900__platform_extensions.sql` (`IF NOT EXISTS`); `deploy/postgres/init` их лишь дублирует. Строка `CREATE EXTENSION` в `telemetry_init` осталась исторически и безвредна (менять применённую миграцию нельзя) |

Проверка без Maven (быстрый цикл): поднятый compose + отдельная БД

```bash
docker exec senso-postgres-1 psql -U senso -d postgres -c "CREATE DATABASE migtest OWNER senso;"
for f in core-app/src/main/resources/db/migration/*/*.sql; do
  docker exec -i senso-postgres-1 psql -U senso -d migtest -v ON_ERROR_STOP=1 -f /dev/stdin < "$f"
done
docker exec senso-postgres-1 psql -U senso -d migtest -Atc \
  "SELECT application_name, schedule_interval FROM timescaledb_information.jobs ORDER BY job_id"
docker exec senso-postgres-1 psql -U senso -d postgres -c "DROP DATABASE migtest;"
```

### 6. RabbitMQ 4.3.6 + MQTT-плагин

| Симптом | Причина | Фикс |
| --- | --- | --- |
| Брокер в crash loop: `assert MaxSizeAuth =< MaxMsgSize` (`rabbit_mqtt.erl:118`) | задан глобальный `max_message_size = 524288`, а MQTT default `max_packet_size_authenticated` больше | в `rabbitmq.conf`: `mqtt.max_packet_size_authenticated = 524288` и `mqtt.max_packet_size_unauthenticated = 524288` |
| `mqtt.max_message_size` — "no setting with that name" | в 4.x такого ключа нет | использовать `max_packet_size_*` (см. выше) |
| `x-dead-letter-exchange` на quorum-очередях | обычный DLX, работает | `q.ingest.raw` → `senso.dlq` / ключ `failed`; `q.dlq.invalid` (`invalid`), `q.dlq.failed` (`failed`) |
| `rabbitmqctl list_queues -v senso` | короткий флаг невалиден | `--vhost senso`; для bindings удобнее management API: `curl -u senso-admin:*** http://localhost:15672/api/bindings/senso` |
| `list_queues` показывает 0 сообщений на quorum | статистика quorum отстаёт | смотреть `/api/queues/senso/q.ingest.raw` (`messages_ready`) |
| Пересчёт пароля для definitions.json | нужен `sha256`-хэш | `python deploy/rabbitmq/hash_password.py <пароль>` |

### 7. ArchUnit 1.5.1

- Нет `ArchConditions.haveSimpleNameMatching` / `JavaClass.Predicates.simpleNameMatching` → свой `DescribedPredicate<JavaClass>("simple name matching .*V\\d+") { test(...) { return getSimpleName().matches(".*V\\d+"); } }` через `ArchConditions.be(...)`.
- `AnalyzeImportAndDeclareDependencies` на пустом множестве классов роняет сборку (`Rule failed to check any classes`) → `.allowEmptyShould(true)` там, где кода под правило ещё нет.
- Тесты не импортируются: `new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("dev.senso.core")`.
- Проверка «правило сработало» без падения: `ArchRule` наследует `CanBeEvaluated` → `EvaluationResult result = rule.evaluate(classes)`,
  `result.hasViolation()`, детали — `result.getFailureReport().getDetails()` (`List<String>`). Метода `getFailureDescription()`
  в 1.5.1 **нет**. `JavaClasses` из `importPackages("a.b", "c.d")` принимает несколько корневых пакетов и включает подпакеты.
- `ApplicationModules.of(CoreApplication.class)` тестовые классы **не** импортирует (подтверждено: в
  `target/spring-modulith-docs` только `identity|devices|telemetry|alerts|realtime|shared`). А component-scan
  `CoreApplication` (базовый пакет `dev.senso.core`) тест-классы видит — потому фикстуры внутри `dev.senso.core..`
  не несут spring/jpa-аннотаций вообще, а «опасные» фикстуры (`@Entity`, `@RestController`, `@RabbitListener`)
  вынесены в `dev.senso.archrules..`.

### 8. openapi-generator 7.25.0 (contract-first)

Работает со Spring 7 / jakarta: generator `spring`, `interfaceOnly=true`, `useSpringBoot3=true`, `useJakartaEe=true`, `openApiNullable=false`, `documentationProvider=none`, `dateLibrary=java8`, `useTags=true`, `skipDefaultInterface=true`; сгенерированы `AuthApi`, `UsersApi`, `DebugApi` + модели в `dev.senso.openapi.{api,model}` (вне `dev.senso.core`: контракт не принадлежит ни одному модулю), компилируются (риск R2 из INIT-PLAN закрыт).
Генерацию не редактировать руками; менять сначала `contracts/openapi/senso-api.v1.yaml`.
`useSpringBoot3=true` — **не** опечатка и не «устарело при Boot 4»: в openapi-generator 7.25 нет флага под Boot 4,
этот флаг выбирает стек Spring 6+/jakarta (`jakarta.*`, `ProblemDetail`, virtual threads-friendly сигнатуры).
«Исправлять» его на `useSpringBoot2` нельзя — получим `javax.*` и неработающий код. Рядом в `core-app/pom.xml`
стоит тот же комментарий, чтобы вопрос не возникал снова.

### 9. Windows / Git Bash

- **CWD сохраняется между вызовами Bash** — после `cd contracts/...` следующая команда `./mvnw` запускается не из корня (потеряли один прогон). Иметь `dir_path`/абсолютные пути.
- `/tmp` в Git Bash != Windows-путь для java/unzip; для распаковки jar лучше python `zipfile` с Windows-путём.
- JDK по умолчанию 23, target 21 (`maven.compiler.release=21`) — не путать `java -version` и требования CI (temurin 21).
- Изучение чужих API: `javap -classpath <jar> <class>` по jar из `~/.m2/repository` — быстрее, чем гадать по статьям.
- **Хостовый сервис важнее опубликованного порта контейнера.** На этой машине служба `postgres.exe` слушает
  `0.0.0.0:5432`, и `jdbc:postgresql://localhost:5432` уходит в неё, а не в `senso-postgres-1`, хотя
  `docker port senso-postgres-1` показывает `127.0.0.1:5432`. Диагностика: `netstat -ano | grep :5432` + `tasklist`,
  `docker port <ctr>`, `docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' <ctr>`
  (IP контейнера с хоста WSL2 не маршрутизируется — проверять `/dev/tcp/<ip>/5432`).
- IP-адрес контейнера из Git Bash: пути в `docker exec` ломаются (`MSYS` переписывает `/var/...` → `C:/Program Files/Git/var/...`),
  помогает `MSYS_NO_PATHCONV=1 docker exec …`.
- Текст ошибки PostgreSQL приходит **от сервера БД** (у хостового сервиса `lc_messages` = русский) и дополнительно
  портится кодовой страницей консоли → в логе кракозябры; читаемый вариант искать в `docker logs senso-postgres-1`
  (там UTF-8 и английский) и в `.txt`-отчётах surefire.
- Убить запущенное приложение: `jps -l` (видно `dev.senso.core.CoreApplication` + pid) → `taskkill //PID <pid> //F`
  (в Git Bash двойной слеш обязателен).

---

## 10. Flyway: миграции из разных веток

| Симптом | Причина | Фикс |
| --- | --- | --- |
| `Validate failed: Detected resolved migration not applied to database` после merge | в develop влилась миграция с версией **меньше** уже применённой (ветки живут параллельно) | `spring.flyway.out-of-order: true` уже включён в `application.yml`; если всё же упало — проверь, что файл не переименовывали после применения |
| `Migration checksum mismatch` | правили уже применённую миграцию | никогда не править применённые; локально — `docker compose -f deploy/docker-compose.yml down -v` и поднять заново |

## 11. Старт приложения

| Симптом | Причина | Фикс |
| --- | --- | --- |
| `core-app needs a role profile: api, worker or local` | запуск без профиля (IDE, тест без `@ActiveProfiles`) | `-Dspring-boot.run.profiles=local`, в IDE — Active profiles, в тесте — `@ActiveProfiles("api")` |
| `Ambiguous @ExceptionHandler method mapped for ...` при старте | в `ProblemDetailsAdvice` добавили `@ExceptionHandler` на исключение, которое уже обрабатывает `ResponseEntityExceptionHandler` | переопредели соответствующий `handle…` метод базового класса вместо нового обработчика |

## 12. Spring Modulith: устаревшая структура модулей в `/actuator/modulith`

| Симптом | Причина | Фикс |
| --- | --- | --- |
| `/actuator/modulith` показывает старые `allowedDependencies`/тип модуля, хотя `package-info.java` уже другой и `ArchitectureTest` зелёный | в `core-app/target/classes/META-INF/spring-modulith/application-modules.json` лежит предвычисленная структура от прошлой сборки, и actuator читает её вместо сканирования классов; инкрементальная сборка файл не обновляет | `./mvnw clean …` (в CI сборка всегда чистая). Проверять границы модулей по `ArchitectureTest`, а не по actuator |
