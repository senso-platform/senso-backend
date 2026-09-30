package dev.senso.kernel.error;

/** Base for all domain errors; mapped to RFC 9457 Problem Details by the web layer (AGENTS.md §4). */
public class SensoException extends RuntimeException {

    private final String problemType;
    private final int status;

    public SensoException(String problemType, int status, String message) {
        super(message);
        this.problemType = problemType;
        this.status = status;
    }

    public SensoException(String problemType, int status, String message, Throwable cause) {
        super(message, cause);
        this.problemType = problemType;
        this.status = status;
    }

    public String getProblemType() {
        return problemType;
    }

    public int getStatus() {
        return status;
    }
}
