# Онбординг: с нуля до первого PR

Цель — за ~30 минут получить зелёный `./mvnw verify` и запущенный `core-app` у себя. Каждый шаг заканчивается
проверкой «что должно получиться». Не совпало — сначала [TROUBLESHOOTING.md](TROUBLESHOOTING.md), потом вопрос @NikitkaTs.

Перед началом прочитай (15 минут, без этого PR будут возвращаться):
[`AGENTS.md`](../../AGENTS.md) — правила, которые проверяет CI; [`docs/modules/README.md`](../modules/README.md) —
куда класть код. Если работаешь с нейронкой, она читает `AGENTS.md` сама — не спорь с ним в промптах.

## 0. Доступы и софт

- [ ] Доступ к GitHub-репозиториям `senso-backend` **и** `senso-contracts` (второй подключён сабмодулем; без доступа
      клон упадёт на `contracts`). Попроси @NikitkaTs добавить тебя.
- [ ] **JDK 21** (Temurin). Именно 21, как в CI. `java -version` → `21.x`.
      Если стоит другая версия — поставь 21 рядом и выбери её в IDEA (Project Structure → SDK) и в `JAVA_HOME`.
- [ ] **Docker Desktop** (Windows/macOS) или Docker Engine + Compose v2. `docker compose version` → `v2.x`.
      На Windows — с WSL2-бэкендом. Docker нужен и для запуска инфраструктуры, и для интеграционных тестов.
- [ ] Git. На Windows дальше все команды — в **Git Bash** (или `mvnw.cmd` вместо `./mvnw` в PowerShell).
- [ ] IntelliJ IDEA (Community хватает).

Maven ставить не нужно: в репозитории есть `./mvnw`.

## 1. Клон

```bash
git clone --recurse-submodules git@github.com:<org>/senso-backend.git
cd senso-backend
git checkout develop
```

Проверка: `ls contracts/mqtt` показывает `envelope.schema.json`, `payloads/`, `examples/`.
Папка пустая → `git submodule update --init --recursive`.

## 2. Инфраструктура (PostgreSQL + TimescaleDB, RabbitMQ + MQTT)

```bash
cp deploy/.env.example deploy/.env
docker compose -f deploy/docker-compose.yml up -d
docker compose -f deploy/docker-compose.yml ps
```

Проверка: оба сервиса `healthy` (RabbitMQ — до минуты). RabbitMQ UI открывается: http://localhost:15672,
`senso-admin` / `admin-dev-pass`, в Queues видны `q.ingest.raw`, `q.dlq.invalid`, `q.dlq.failed`.

**Порт 5432 занят** (на Windows часто стоит своя служба PostgreSQL — `netstat -ano | findstr :5432`) (ЕСЛИ ЖЕ НЕ ЗАНЯТО, ТО ОСТАВЬ 5432 И ПРОВЕРЬ, ЧТО ВЕЗДЕ 5432):
в своём `deploy/.env` поставь `SENSO_DB_PORT=5433` и `SENSO_DB_URL=jdbc:postgresql://localhost:5433/senso`,
пересоздай контейнер (`up -d`). Приложению этот же URL передаётся переменной окружения (шаг 4) — `deploy/.env`
читает только docker compose. Трекаемые файлы с портами **не правь**.

## 3. Полная сборка

```bash
./mvnw -B -ntp verify > verify.log 2>&1; echo exit=$?
grep -E "Tests run:|BUILD|FAIL" verify.log
```

Проверка: `exit=0`, в каждом модуле `Failures: 0, Errors: 0`. Первая сборка долгая (скачивает зависимости и
Docker-образы для Testcontainers), дальше — пара минут.

Код возврата смотри через `echo exit=$?`, а не через `| tail` — пайп подменяет код возврата Maven.
Упало на `spotless:check` → `./mvnw spotless:apply` и заново.

## 4. Запуск `core-app`

Профиль `local` = роли `api` и `worker` в одном процессе, дефолтные адреса и пароли под compose.

```bash
./mvnw -pl core-app -am spring-boot:run -Dspring-boot.run.profiles=local
# порт БД не 5432:
SENSO_DB_URL=jdbc:postgresql://localhost:5433/senso ./mvnw -pl core-app -am spring-boot:run -Dspring-boot.run.profiles=local
```

Проверка:

```bash
curl -s localhost:8080/actuator/health          # {"status":"UP",...}
curl -i localhost:8080/api/v1/nope              # 404, application/problem+json, есть traceId
```

Без профиля приложение не стартует с сообщением `core-app needs a role profile` — так и задумано.

## 5. IntelliJ IDEA

- [ ] Открыть корневой `pom.xml` как проект. После первого `./mvnw verify` — Maven → **Reload All Maven Projects**:
      появятся сгенерированные из OpenAPI интерфейсы (`core-app/target/generated-sources/openapi`,
      пакет `dev.senso.openapi`). Красные импорты `dev.senso.openapi.*` = не было сборки или reload.
- [ ] Settings → Build → Compiler → Annotation Processors → **Enable annotation processing** (MapStruct).
- [ ] Run Configuration для `CoreApplication`: **Active profiles** = `local`; если порт БД не 5432 —
      Environment variables: `SENSO_DB_URL=jdbc:postgresql://localhost:5433/senso`.
      Пустую или неполную `SENSO_DB_URL` не оставляй: она перекрывает дефолт и роняет старт (`'url' must start with "jdbc"`).
- [ ] Форматирование: код форматирует `./mvnw spotless:apply` (palantir-java-format). Можно поставить плагин
      palantir-java-format в IDEA, но источник истины — spotless.
- [ ] Тесты из IDEA: unit (`*Test`) запускаются сразу, интеграционные (`*IT`) — при запущенном Docker.

## 6. Первая задача и PR

```bash
git checkout develop && git pull --recurse-submodules
git checkout -b feature/SCRUM-<n>-<коротко>
# ... код + тесты ...
./mvnw spotless:apply
./mvnw -B -ntp verify > verify.log 2>&1; echo exit=$?
git commit -m "feat(<module>): <что сделано>"      # Conventional Commits
git push -u origin HEAD                              # → PR в develop
```

Перед PR сверься:

- [ ] код в своём модуле: `internal` для реализации, `api` — только то, что нужно другим модулям;
- [ ] контроллер реализует сгенерированный интерфейс и помечен `@ApiRole`; нужен новый эндпоинт — сначала PR в `senso-contracts`;
- [ ] миграция `db/migration/<module>/V<yyyyMMddHHmm>__<module>_<что>.sql`, имена таблиц со схемой;
- [ ] есть unit-тест и, для данных пользователя, негативный тест владения (`OwnershipTestSupport`);
- [ ] `./mvnw verify` зелёный; в описании PR: что сделано, как проверить, какие контракты/миграции затронуты.

PR в `develop` не вольётся без зелёного CI (`build` + `contracts`) и ревью владельца кода (`.github/CODEOWNERS`).
Красный `ArchitectureTest` значит, что нарушена граница модуля или правило из `AGENTS.md` — правило не ослабляем,
меняем код (или обсуждаем с @NikitkaTs, если правило мешает задаче).

## Полезное

| Что | Как |
| --- | --- |
| Сбросить БД и брокер начисто | `docker compose -f deploy/docker-compose.yml down -v` и снова `up -d` |
| Только архитектурные тесты, без Docker | `./mvnw -pl core-app -am test -Dtest='ArchitectureTest,ArchRuleFixturesTest' -Dsurefire.failIfNoSpecifiedTests=false` |
| Документация модулей (диаграммы) | `./mvnw -pl core-app -am test -Dtest=ArchitectureTest -Dmodulith.docs=true -Dsurefire.failIfNoSpecifiedTests=false` → `core-app/target/spring-modulith-docs` |
| Проверить MQTT руками | `deploy/README.md`, раздел «Проверка MQTT» |
| Обновить контракты до свежего коммита | Dependabot присылает PR сам; руками — `git submodule update --remote contracts` отдельным PR |
| Странная ошибка сборки/старта | [TROUBLESHOOTING.md](TROUBLESHOOTING.md) |
