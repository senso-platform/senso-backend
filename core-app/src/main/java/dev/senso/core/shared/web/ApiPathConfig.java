package dev.senso.core.shared.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Префикс REST API из контракта ({@code servers: /api/v1} в {@code contracts/openapi/senso-api.v1.yaml}).
 * Генератор OpenAPI переносит в интерфейсы только {@code paths} ({@code /auth/login}), базовый путь задаётся здесь.
 *
 * <p>Префикс получают только {@code @RestController} из {@code dev.senso.core}: actuator ({@code /actuator/**}) и
 * {@code /error} остаются на своих путях. {@code server.servlet.context-path} не подходит — он перенёс бы и
 * health-пробы.
 */
@Configuration(proxyBeanMethods = false)
public class ApiPathConfig implements WebMvcConfigurer {

    public static final String API_PREFIX = "/api/v1";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(
                API_PREFIX,
                HandlerTypePredicate.forBasePackage("dev.senso.core")
                        .and(HandlerTypePredicate.forAnnotation(RestController.class)));
    }
}
