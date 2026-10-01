package dev.senso.webslice;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.senso.core.shared.web.ProblemDetailsAdvice;
import dev.senso.core.shared.web.TraceIdFilter;
import dev.senso.kernel.error.NotFoundException;
import dev.senso.kernel.error.ProblemTypes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Контракт ошибок API (ADR-0001 §9.1): что видит фронт при каждой категории ошибки.
 *
 * <p>Пакет вне dev.senso.core намеренно: тестовый {@code @RestController} не должен попасть в component scan
 * интеграционных тестов. Контекст Spring не поднимается — standalone MockMvc, тест быстрый.
 */
class ProblemDetailsAdviceTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new ProblemDetailsAdvice())
            .addFilters(new TraceIdFilter())
            .build();

    @Test
    void domainExceptionBecomesItsStatusWithTypeAndTraceId() throws Exception {
        mvc.perform(get("/probe/not-found").header(TraceIdFilter.HEADER, "trace-123"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ProblemTypes.NOT_FOUND))
                .andExpect(jsonPath("$.detail").value("gateway not found"))
                .andExpect(jsonPath("$.traceId").value("trace-123"))
                .andExpect(header().string(TraceIdFilter.HEADER, "trace-123"));
    }

    @Test
    void unexpectedExceptionIs500WithoutInternals() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Internal server error"))
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(content().string(not(containsString("db-password"))));
    }

    @Test
    void invalidBodyIs400WithFieldErrors() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"count\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(ProblemTypes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void malformedJsonIs400Not500() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void constraintOnRequestParamIs400WithFieldError() throws Exception {
        mvc.perform(get("/probe/page").param("limit", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(ProblemTypes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
    }

    @Test
    void wrongParamTypeIs400Not500() throws Exception {
        mvc.perform(get("/probe/page").param("limit", "abc")).andExpect(status().isBadRequest());
    }

    @Test
    void wrongMethodIs405Not500() throws Exception {
        mvc.perform(delete("/probe/not-found")).andExpect(status().isMethodNotAllowed());
    }

    record ProbeBody(@NotBlank String name, @Min(1) int count) {}

    @RestController
    static class ProbeController {

        @GetMapping("/probe/not-found")
        void notFound() {
            throw new NotFoundException("gateway not found");
        }

        @GetMapping("/probe/boom")
        void boom() {
            throw new IllegalStateException("connection failed, db-password=hunter2");
        }

        @PostMapping("/probe/body")
        void body(@Valid @RequestBody ProbeBody body) {}

        @GetMapping("/probe/page")
        int page(@RequestParam("limit") @Max(100) int limit) {
            return limit;
        }
    }
}
