package dev.senso.kernel.error;

public class ValidationException extends SensoException {

    public ValidationException(String message) {
        super(ProblemTypes.VALIDATION_FAILED, 400, message);
    }

    public ValidationException(String message, Throwable cause) {
        super(ProblemTypes.VALIDATION_FAILED, 400, message, cause);
    }
}
