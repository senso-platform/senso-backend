package dev.senso.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/** Профиль local = api + worker в одном процессе: бины обеих ролей уживаются, Modulith-эндпоинт открыт. */
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CoreLocalContextIT extends IntegrationTestBase {

    @LocalServerPort
    int port;

    @Test
    void localRunsBothRolesAndExposesModulithEndpoint() {
        HttpStatusCode status = RestClient.create()
                .get()
                .uri("http://localhost:%d/actuator/modulith".formatted(port))
                .retrieve()
                .toBodilessEntity()
                .getStatusCode();
        assertThat(status.is2xxSuccessful()).isTrue();
    }
}
