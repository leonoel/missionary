package missionary;

public class ProtocolViolation extends RuntimeException {
    public ProtocolViolation(String message) {
        super(message);
    }

    public ProtocolViolation(String message, Throwable cause) {
        super(message, cause);
    }
}
