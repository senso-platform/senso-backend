package dev.senso.kernel.error;

/** RFC 9457 problem type URIs (ADR-0001 §9.1). */
public final class ProblemTypes {

    public static final String VALIDATION_FAILED = "https://senso.dev/problems/validation-failed";
    public static final String UNAUTHORIZED = "https://senso.dev/problems/unauthorized";
    public static final String FORBIDDEN = "https://senso.dev/problems/forbidden";
    public static final String NOT_FOUND = "https://senso.dev/problems/not-found";
    public static final String CONFLICT = "https://senso.dev/problems/conflict";
    public static final String RATE_LIMITED = "https://senso.dev/problems/rate-limited";

    private ProblemTypes() {}
}
