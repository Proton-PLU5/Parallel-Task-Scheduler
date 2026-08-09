package se306.scheduler.cli;

/**
 * Thrown when the command line does not match {@code INPUT.dot P [-p N] [-v] [-o OUTPUT]} — a
 * missing positional argument, a non-positive count, an option without its value, or an option we
 * don't know.
 *
 * <p>The message describes the specific problem; {@code Main} pairs it with the usage text.
 */
public class CliArgumentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CliArgumentException(String message) {
        super(message);
    }
}
