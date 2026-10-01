package dev.senso.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * Роль worker поднимается отдельно от api. Ловит бин, который нужен worker-у, но объявлен только под
 * {@code @ApiRole} (или наоборот) — такое иначе всплывает только на стенде.
 */
@ActiveProfiles("worker")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CoreWorkerContextIT extends IntegrationTestBase {

    @Autowired
    ApplicationContext context;

    @LocalServerPort
    int port;

    @Test
    @SuppressWarnings("unchecked")
    void workerStartsWithSchedulerLockAndHealthUp() {
        assertThat(context.getBeansOfType(LockProvider.class)).hasSize(1);
        Map<String, Object> health = RestClient.create()
                .get()
                .uri("http://localhost:%d/actuator/health".formatted(port))
                .retrieve()
                .body(Map.class);
        assertThat(health).isNotNull();
        assertThat(health.get("status")).isEqualTo("UP");
    }
}
