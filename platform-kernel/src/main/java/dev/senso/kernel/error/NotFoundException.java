package dev.senso.kernel.error;

public class NotFoundException extends SensoException {

    public NotFoundException(String message) {
        super(ProblemTypes.NOT_FOUND, 404, message);
    }

    public NotFoundException(String message, Throwable cause) {
        super(ProblemTypes.NOT_FOUND, 404, message, cause);
    }
}
