package dev.senso.kernel.error;

public class ForbiddenException extends SensoException {

    public ForbiddenException(String message) {
        super(ProblemTypes.FORBIDDEN, 403, message);
    }

    public ForbiddenException(String message, Throwable cause) {
        super(ProblemTypes.FORBIDDEN, 403, message, cause);
    }
}
