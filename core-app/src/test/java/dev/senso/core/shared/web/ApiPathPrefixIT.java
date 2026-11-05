package dev.senso.core.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.senso.core.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * Эндпоинты живут под {@code /api/v1}, как в {@code servers} контракта; actuator — без префикса.
 * Конфигурация совпадает с {@code CoreApplicationIT}, поэтому контекст переиспользуется.
 */
@ActiveProfiles("api")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiPathPrefixIT extends IntegrationTestBase {

    private static final String UNKNOWN_LOGIN =
            "{\"email\":\"nobody@example.com\",\"password\":\"wrong-password-123\"}";

    @LocalServerPort
    int port;

    @Test
    void contractPathsAreServedUnderApiPrefix() {
        assertThat(postLogin("/api/v1/auth/login"))
                .as("неверный вход по пути из контракта")
                .isEqualTo(401);
    }

    @Test
    void pathsWithoutPrefixAreNotMapped() {
        assertThat(postLogin("/auth/login")).isEqualTo(404);
    }

    @Test
    void actuatorStaysWithoutPrefix() {
        assertThat(get("/actuator/health")).isEqualTo(200);
        assertThat(get("/api/v1/actuator/health")).isEqualTo(404);
    }

    private int postLogin(String path) {
        return RestClient.create()
                .post()
                .uri("http://localhost:%d%s".formatted(port, path))
                .contentType(MediaType.APPLICATION_JSON)
                .body(UNKNOWN_LOGIN)
                .exchange((request, response) -> response.getStatusCode().value());
    }

    private int get(String path) {
        return RestClient.create()
                .get()
                .uri("http://localhost:%d%s".formatted(port, path))
                .exchange((request, response) -> response.getStatusCode().value());
    }
}
