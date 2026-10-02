package dev.senso.core.shared.web;

import dev.senso.kernel.error.ProblemTypes;
import dev.senso.kernel.error.SensoException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Единственная точка превращения исключений в RFC 9457 Problem Details (AGENTS.md §4).
 *
 * <p>Наследует {@link ResponseEntityExceptionHandler}: стандартные исключения Spring MVC (кривой JSON, неизвестный
 * URL, неверный метод, тип или отсутствующий параметр, нарушения ограничений на параметрах сгенерированных
 * интерфейсов) получают свой 4xx, а не 500. Обработчики для этих исключений здесь объявлять нельзя: два
 * {@code @ExceptionHandler} на один тип роняют старт контекста. Нужна другая форма ответа — переопредели
 * соответствующий {@code handle…} метод базового класса.
 *
 * <p>Все ответы получают {@code traceId} в {@link #createResponseEntity}.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    static final String VALIDATION_DETAIL = "Validation failed";

    @ExceptionHandler(SensoException.class)
    public ResponseEntity<Object> handleSensoException(SensoException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatus());
        if (status.is5xxServerError()) {
            log.error("domain error on {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage(), ex);
        } else {
            log.debug("domain error on {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setType(URI.create(ex.getProblemType()));
        return ResponseEntity.status(status).body(withTraceId(problem));
    }

    /** {@code @Valid @RequestBody}: ошибки полей и ошибки уровня объекта. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(e -> errors.add(error(e.getField(), e)));
        ex.getBindingResult().getGlobalErrors().forEach(e -> errors.add(error(e.getObjectName(), e)));
        return validationProblem(errors, headers, request);
    }

    /** Ограничения на {@code @RequestParam}/{@code @PathVariable} (встроенная валидация методов Spring MVC). */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String name = Objects.requireNonNullElse(result.getMethodParameter().getParameterName(), "argument");
            result.getResolvableErrors().forEach(e -> errors.add(error(name, e)));
        });
        return validationProblem(errors, headers, request);
    }

    /** Валидация через AOP-прокси ({@code @Validated} на классе): те же 400 и {@code errors[]}. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getConstraintViolations()
                .forEach(v -> errors.add(error(String.valueOf(v.getPropertyPath()), v.getMessage())));
        return validationProblem(errors, new HttpHeaders(), request);
    }

    /**
     * Всё непредусмотренное — 500 без деталей наружу (stacktrace только в лог).
     *
     * <p>Исключения Spring Security пробрасываются дальше: их превращает в 401/403 фильтр безопасности
     * ({@code ExceptionTranslationFilter}). Иначе {@code AccessDeniedException} из method security стал бы 500.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) throws Exception {
        if (ex.getClass().getName().startsWith("org.springframework.security.")) {
            throw ex;
        }
        log.error("unexpected error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
        return ResponseEntity.internalServerError().body(withTraceId(problem));
    }

    /** Общая точка выхода для всех ответов базового класса: добавляем traceId. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            withTraceId(problem);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ResponseEntity<Object> validationProblem(
            List<Map<String, String>> errors, HttpHeaders headers, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, VALIDATION_DETAIL);
        problem.setType(URI.create(ProblemTypes.VALIDATION_FAILED));
        problem.setProperty("errors", errors);
        return createResponseEntity(problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    private static Map<String, String> error(String field, MessageSourceResolvable error) {
        String message = error.getDefaultMessage();
        if (message == null && error instanceof FieldError fieldError) {
            message = fieldError.getCode();
        }
        return error(field, message);
    }

    private static Map<String, String> error(String field, String message) {
        // LinkedHashMap, а не Map.of: Map.of бросает NPE на null-сообщении.
        Map<String, String> error = new LinkedHashMap<>();
        error.put("field", field);
        error.put("message", message == null ? "invalid" : message);
        return error;
    }

    private static ProblemDetail withTraceId(ProblemDetail problem) {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }
}
