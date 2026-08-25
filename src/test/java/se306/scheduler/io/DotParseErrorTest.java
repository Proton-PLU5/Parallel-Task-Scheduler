package se306.scheduler.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the parser's error handling: input that is not DOT at all, and DOT that is well-formed but
 * unusable because a task or dependency has no integer {@code Weight}. A bad input must always
 * fail with a clear message — it must never quietly turn into a smaller graph.
 */
class DotParseErrorTest {

    private final DotParser parser = new DotParser();

    @Test
    @DisplayName("input that is not a DOT file at all is rejected")
    void malformedDotRejected() {
        DotParseException error = assertThrows(DotParseException.class,
                () -> parser.parse("this is not a dot file"));

        assertTrue(error.getMessage().contains("not valid DOT"), error.getMessage());
    }

    @Test
    @DisplayName("a task with no Weight attribute is rejected")
    void taskWithoutWeightRejected() {
        // B is declared but never given a Weight, so we cannot know how long it runs for.
        String source = """
                digraph g {
                    A [Weight=2];
                    B;
                    A -> B [Weight=1];
                }
                """;

        DotParseException error = assertThrows(DotParseException.class,
                () -> parser.parse(source));

        assertTrue(error.getMessage().contains("task 'B'"), error.getMessage());
        assertTrue(error.getMessage().contains("no Weight"), error.getMessage());
    }

    @Test
    @DisplayName("a dependency with no Weight attribute is rejected")
    void edgeWithoutWeightRejected() {
        // The edge A -> B has no Weight, so we cannot know its communication cost.
        String source = """
                digraph g {
                    A [Weight=2];
                    B [Weight=3];
                    A -> B;
                }
                """;

        DotParseException error = assertThrows(DotParseException.class,
                () -> parser.parse(source));

        assertTrue(error.getMessage().contains("'A -> B'"), error.getMessage());
        assertTrue(error.getMessage().contains("no Weight"), error.getMessage());
    }

    @Test
    @DisplayName("a task Weight that is not a whole number is rejected")
    void nonIntegerTaskWeightRejected() {
        String source = """
                digraph g {
                    A [Weight="banana"];
                }
                """;

        DotParseException error = assertThrows(DotParseException.class,
                () -> parser.parse(source));

        assertTrue(error.getMessage().contains("not an integer"), error.getMessage());
        assertTrue(error.getMessage().contains("task 'A'"), error.getMessage());
    }

    @Test
    @DisplayName("a dependency Weight that is not a whole number is rejected")
    void nonIntegerEdgeWeightRejected() {
        String source = """
                digraph g {
                    A [Weight=2];
                    B [Weight=3];
                    A -> B [Weight="1.5"];
                }
                """;

        DotParseException error = assertThrows(DotParseException.class,
                () -> parser.parse(source));

        assertTrue(error.getMessage().contains("not an integer"), error.getMessage());
        assertTrue(error.getMessage().contains("'A -> B'"), error.getMessage());
    }

    @Test
    @DisplayName("when the line number is known, the message starts with it")
    void lineNumberReportedWhenKnown() {
        DotParseException error = new DotParseException(3, "boom");

        assertEquals("line 3: boom", error.getMessage());
        assertEquals(3, error.line());
    }

    @Test
    @DisplayName("line 0 means the line is unknown, so the message has no line prefix")
    void unknownLineNumberOmitted() {
        DotParseException error = new DotParseException(0, "boom");

        assertEquals("boom", error.getMessage());
        assertEquals(0, error.line());
    }
}
