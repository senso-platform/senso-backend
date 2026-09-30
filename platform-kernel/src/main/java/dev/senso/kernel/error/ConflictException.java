package dev.senso.kernel.error;

public class ConflictException extends SensoException {

    public ConflictException(String message) {
        super(ProblemTypes.CONFLICT, 409, message);
    }

    public ConflictException(String message, Throwable cause) {
        super(ProblemTypes.CONFLICT, 409, message, cause);
    }
}
