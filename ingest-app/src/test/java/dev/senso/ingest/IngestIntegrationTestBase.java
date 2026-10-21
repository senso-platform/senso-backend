package dev.senso.ingest;

import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Брокер для IT ingest с той же конфигурацией, что в deploy/docker-compose.yml: rabbitmq.conf, плагины и
 * definitions.json копируются из {@code deploy/rabbitmq}. Тест работает на vhost {@code senso} под
 * {@code senso-ingest}, а не на {@code /} — так проверяются и права из definitions.json.
 */
public abstract class IngestIntegrationTestBase {

    private static final DockerImageName RABBIT_IMAGE = DockerImageName.parse("rabbitmq:4.3.6-management");
    private static final Path RABBIT_CONFIG = Path.of("..", "deploy", "rabbitmq");

    /** Dev-пароли из deploy/.env.example (хэши в definitions.json). */
    protected static final String INGEST_USER = "senso-ingest";

    protected static final String INGEST_PASSWORD = "ingest-dev-pass";
    protected static final String ADMIN_USER = "senso-admin";
    protected static final String ADMIN_PASSWORD = "admin-dev-pass";
    protected static final String VHOST = "senso";

    protected static final RabbitMQContainer RABBIT = new RabbitMQContainer(RABBIT_IMAGE)
            .withCopyFileToContainer(config("rabbitmq.conf"), "/etc/rabbitmq/rabbitmq.conf")
            .withCopyFileToContainer(config("enabled_plugins"), "/etc/rabbitmq/enabled_plugins")
            .withCopyFileToContainer(config("definitions.json"), "/etc/rabbitmq/definitions.json")
            .withReuse(true);

    static {
        RABBIT.start();
    }

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> INGEST_USER);
        registry.add("spring.rabbitmq.password", () -> INGEST_PASSWORD);
        registry.add("spring.rabbitmq.virtual-host", () -> VHOST);
        // число повторов как в проде (application.yml), паузы короче — чтобы unroutable-сценарий шёл за секунду
        registry.add("spring.rabbitmq.listener.simple.retry.initial-interval", () -> "100ms");
        registry.add("spring.rabbitmq.listener.simple.retry.max-interval", () -> "200ms");
    }

    private static MountableFile config(String name) {
        return MountableFile.forHostPath(RABBIT_CONFIG.resolve(name).toAbsolutePath());
    }
}
