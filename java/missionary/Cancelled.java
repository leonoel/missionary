package missionary;

public class Cancelled extends Throwable {
    public Cancelled() {
        this(null);
    }

    public Cancelled(String message) {
        super(message, null, false, false);
    }
}
