package se306.scheduler.io;

/**
 * Thrown when a DOT statement that should describe a task or dependency cannot be understood — a
 * missing {@code Weight}, a non-integer weight, or an unparseable node name.
 *
 * <p>Statements the parser does not recognise at all (graph attributes, comments, subgraph braces)
 * are skipped silently rather than reported here; the project requires other DOT syntax to be
 * ignored gracefully.
 */
public class DotParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int line;

    public DotParseException(int line, String message) {
        super(line > 0 ? "line " + line + ": " + message : message);
        this.line = line;
    }

    /** The 1-based input line the offending statement started on, or 0 if unknown. */
    public int line() {
        return line;
    }
}
