package dev.senso.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * Модульный монолит SensoBazaar (ADR-0001). Пакеты первого уровня — модули Spring Modulith,
 * границы и разрешённые зависимости проверяет {@code ArchitectureTest}.
 *
 * <p>{@code sharedModules = "shared"} влияет только на {@code @ApplicationModuleTest}: модуль {@code shared}
 * всегда поднимается вместе с тестируемым. Доступ к {@code shared} из других модулей открывают
 * {@code shared/package-info.java} (тип OPEN) и {@code "shared"} в {@code allowedDependencies} каждого модуля.
 *
 * <p>{@code Clock} приходит автоконфигурацией из {@code platform-kernel} ({@code ClockConfig}).
 */
@Modulithic(sharedModules = "shared")
@SpringBootApplication
public class CoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreApplication.class, args);
    }
}
