package app.unichat.matrix;

/**
 * Error returned by the homeserver ({@code errcode} like {@code M_FORBIDDEN}) or a failure with a
 * user-facing Spanish message. {@code status} is the HTTP status, 0 when no response was received.
 */
public final class MatrixException extends RuntimeException {
    private final int status;
    private final String errcode;

    public MatrixException(int status, String errcode, String message) {
        super(message);
        this.status = status;
        this.errcode = errcode;
    }

    public MatrixException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.errcode = null;
    }

    public int status() { return status; }
    public String errcode() { return errcode; }

    boolean isUnrecognized() {
        return status == 404 || status == 405 || "M_UNRECOGNIZED".equals(errcode);
    }
}
