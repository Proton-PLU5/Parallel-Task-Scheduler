package se306.scheduler.graph;

/**
 * Thrown by {@link GraphBuilder#build()} when the declared graph is not a valid task graph — a
 * dangling edge endpoint, a self-loop, a negative weight, a contradictory redeclaration, or a cycle.
 */
public class GraphValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GraphValidationException(String message) {
        super(message);
    }
}