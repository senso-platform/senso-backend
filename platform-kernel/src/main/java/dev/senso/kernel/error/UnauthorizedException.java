package dev.senso.kernel.error;

public class UnauthorizedException extends SensoException {

    public UnauthorizedException(String message) {
        super(ProblemTypes.UNAUTHORIZED, 401, message);
    }
}
