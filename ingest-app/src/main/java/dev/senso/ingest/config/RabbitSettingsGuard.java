package dev.senso.ingest.config;

import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Не даёт стартовать без адреса и учётки брокера. В application.yml значений по умолчанию нет намеренно (в контейнере
 * их задаёт окружение), а Boot оставляет неразрешённый {@code ${SENSO_RABBIT_HOST}} строкой — без этой проверки
 * приложение «стартует» и бесконечно ловит {@code UnknownHostException: ${SENSO_RABBIT_HOST}}.
 */
@Component
public class RabbitSettingsGuard implements InitializingBean {

    static final List<String> REQUIRED =
            List.of("spring.rabbitmq.host", "spring.rabbitmq.username", "spring.rabbitmq.password");

    private final Environment environment;

    public RabbitSettingsGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> missing = REQUIRED.stream().filter(this::unresolved).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("ingest-app has no RabbitMQ settings " + missing
                    + ": run with profile local (defaults for deploy/docker-compose.yml) or set"
                    + " SENSO_RABBIT_HOST, SENSO_RABBIT_USER, SENSO_RABBIT_PASSWORD.");
        }
    }

    private boolean unresolved(String key) {
        String value;
        try {
            value = environment.getProperty(key);
        } catch (IllegalArgumentException e) { // Could not resolve placeholder
            return true;
        }
        return value == null || value.isBlank() || value.contains("${");
    }
}
