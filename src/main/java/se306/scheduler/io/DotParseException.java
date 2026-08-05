package se306.scheduler.io;

/**
 * Thrown when a line of a DOT file is not one the parser accepts — a task declaration, a dependency,
 * the graph header or a closing brace.
 *
 * <p>Every unrecognised line is reported, so a typo fails loudly instead of quietly producing a
 * graph with tasks missing from it.
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
