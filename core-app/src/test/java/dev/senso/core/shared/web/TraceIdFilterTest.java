package dev.senso.core.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TraceIdFilterTest {

    @Test
    void keepsSafeClientTraceId() {
        assertThat(TraceIdFilter.sanitize("4bf92f3577b34da6a3ce929d0e0e4736"))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void replacesMissingOrUnsafeTraceId() {
        for (String unsafe : new String[] {null, "", "a\nFAKE LOG LINE", "x".repeat(65), "<script>"}) {
            String result = TraceIdFilter.sanitize(unsafe);
            assertThat(result).as("input %s", unsafe).isNotEqualTo(unsafe);
            assertThat(UUID.fromString(result)).isNotNull();
        }
    }
}
