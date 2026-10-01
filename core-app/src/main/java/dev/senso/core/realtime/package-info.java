/**
 * Модуль realtime: SSE-выдача телеметрии и алертов подключённым клиентам. Схемы БД не имеет.
 * Разрешённые зависимости: {@code shared}, {@code devices::api}, {@code alerts::api}.
 */
@ApplicationModule(allowedDependencies = {"shared", "devices::api", "alerts::api"})
package dev.senso.core.realtime;

import org.springframework.modulith.ApplicationModule;
