# Модули `core-app`

Пакеты первого уровня в `dev.senso.core` — модули Spring Modulith. Границы проверяет
`ArchitectureTest` на `./mvnw verify`; нарушить их и не заметить нельзя.

Правила структурирования — в `AGENTS.md` §2–5. Здесь только карта.

## Анатомия модуля

```
dev.senso.core.<module>
├─ package-info.java            # @ApplicationModule(allowedDependencies = …) — whitelist зависимостей
├─ api/                         # @NamedInterface("api") — единственное, что видят другие модули
│  └─ events/                   # межмодульные события: records с суффиксом версии (AlertRaisedV1)
└─ internal/                    # всё остальное: сервисы, JPA-сущности (internal/domain), веб (internal/web),
                                # мапперы MapStruct, листенеры и паблишеры AMQP (internal/messaging)
```

- Другие модули могут импортировать **только** `<module>.api` (типы и события).
- Общая инфраструктура — в `shared` (модуль типа OPEN, разрешён всем через `"shared"` в `allowedDependencies`):
  `shared.role` (`@ApiRole`, `@WorkerRole`, `RoleProfilesGuard`), `shared.web` (`ProblemDetailsAdvice`, `TraceIdFilter`),
  `shared.scheduling` (`@EnableScheduling`, ShedLock на `platform.shedlock`). Доменной логики в `shared` нет.
- Контракт REST сгенерирован в `dev.senso.openapi.{api,model}` — вне модулей; модуль реализует свой интерфейс
  в `<module>.internal.web`.
- Ссылка на объект чужого модуля — `UUID`, не сущность. JOIN между схемами модулей запрещён.

## Карта модулей

| Модуль | Ответственность | Схема БД | Владелец |
| --- | --- | --- |----------|
| `identity` | пользователи, JWT/refresh, регистрация и вход | `identity` | -        |
| `devices` | шлюзы и устройства, ACL владельца | `devices` | -        |
| `telemetry` | приём измерений (worker), hypertable, графики | `telemetry` | -        |
| `alerts` | алерты от IDS шлюза, жизненный цикл | `alerts` | -        |
| `realtime` | SSE-выдача клиентам | — | -        |

Список разрешённых зависимостей каждого модуля — в его `package-info.java` (совпадает с `AGENTS.md` §3.3).

## Куда класть что (шпаргалка)

| Что | Пакет | Обязательно |
| --- | --- | --- |
| JPA-сущность | `<module>.internal.domain` | `@Table(schema = "<module>")`, id из `Ids.newId()` |
| Сервис с бизнес-логикой и проверкой владельца | `<module>.internal` | `@Transactional` здесь, чужое → `NotFoundException` |
| Контроллер | `<module>.internal.web` | `@ApiRole`, `implements <Tag>Api` из `dev.senso.openapi.api` |
| Листенер очереди, планировщик | `<module>.internal.messaging` | `@WorkerRole` (в api — только realtime) |
| Публичный интерфейс для других модулей | `<module>.api` | только records/интерфейсы, без сущностей |
| Событие для других модулей | `<module>.api.events` | `record …V1`, поля: примитивы, `UUID`, `Instant`, `String` |
| Настройки | `<module>.internal` | `@ConfigurationProperties("senso.<module>")` record, не `@Value` |
| Миграция | `core-app/src/main/resources/db/migration/<module>/` | `V<yyyyMMddHHmm>__<module>_<что>.sql`, имена со схемой |
| Тесты | рядом по пакету в `src/test` | unit `*Test`, IT `*IT` + негативный тест через `OwnershipTestSupport` |
