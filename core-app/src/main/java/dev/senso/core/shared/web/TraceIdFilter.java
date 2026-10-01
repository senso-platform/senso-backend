package dev.senso.core.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * traceId для логов и Problem Details (AGENTS.md §4). Берётся из {@code X-Trace-Id}, иначе генерируется.
 *
 * <p>Заголовок приходит от клиента, поэтому принимается только безопасный формат (буквы, цифры, {@code -_.},
 * до 64 символов): иначе через него можно подделать строки лога или раздуть его. Невалидное значение заменяется.
 *
 * <p>Порядок — первым в цепочке, чтобы traceId был и в логах фильтра безопасности. Когда подключим Micrometer
 * Tracing (OpenTelemetry), этот фильтр заменяется трейсингом: он кладёт в MDC тот же ключ {@code traceId}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = sanitize(request.getHeader(HEADER));
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static String sanitize(String header) {
        if (header != null && SAFE_TRACE_ID.matcher(header).matches()) {
            return header;
        }
        return UUID.randomUUID().toString();
    }
}
