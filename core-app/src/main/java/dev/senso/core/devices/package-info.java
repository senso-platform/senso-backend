/**
 * Модуль devices: шлюзы и устройства, ACL владельца. Схема БД {@code devices}.
 * Разрешённые зависимости: {@code shared}, {@code identity::api}.
 */
@ApplicationModule(allowedDependencies = {"shared", "identity::api"})
package dev.senso.core.devices;

import org.springframework.modulith.ApplicationModule;
