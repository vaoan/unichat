package app.unichat.matrix;

import org.jspecify.annotations.Nullable;

/**
 * Error returned by the homeserver ({@code errcode} like {@code M_FORBIDDEN}) or a failure with a
 * user-facing Spanish message. {@code status} is the HTTP status, 0 when no response was received.
 */
public final class MatrixException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final int status;
    private final @Nullable String errcode;

    public MatrixException(int status, @Nullable String errcode, String message) {
        super(message);
        this.status = status;
        this.errcode = errcode;
    }

    public MatrixException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.errcode = null;
    }

    /** Never null: every constructor takes one, and only this package makes them. */
    @Override
    public String getMessage() {
        return java.util.Objects.requireNonNull(super.getMessage(), "every constructor takes a message");
    }

    public int status() { return status; }
    public @Nullable String errcode() { return errcode; }

    boolean isUnrecognized() {
        return status == 404 || status == 405 || "M_UNRECOGNIZED".equals(errcode);
    }
}
