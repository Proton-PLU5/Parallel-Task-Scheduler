package se306.scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se306.scheduler.graph.TaskGraph;
import se306.scheduler.io.DotParser;

/**
 * Runs the whole program end-to-end: command line in, schedule file out.
 *
 * <p>Only the successful paths are tested. The failure paths all call {@link System#exit}, which
 * would shut down the JVM the tests run in, so they cannot be tested without changing Main.
 */
class MainTest {

    /** A two-task graph: A takes 2 units, B takes 3, and B must run after A. */
    private static final String TINY_GRAPH = """
            digraph "tiny" {
                A [Weight=2];
                B [Weight=3];
                A -> B [Weight=1];
            }
            """;

    /** Writes the tiny graph into the temporary directory and returns its path. */
    private static Path writeInput(Path directory) throws IOException {
        Path input = directory.resolve("tiny.dot");
        Files.writeString(input, TINY_GRAPH);
        return input;
    }

    @Test
    @DisplayName("a normal run solves the graph and writes the schedule to the file named by -o")
    void sequentialRunWritesScheduleToNamedOutput(@TempDir Path directory) throws IOException {
        Path input = writeInput(directory);
        Path output = directory.resolve("solved.dot");

        Main.main(new String[] {input.toString(), "2", "-o", output.toString()});

        assertTrue(Files.exists(output), "the schedule file was not written");

        // The written file should be the input graph plus a Start and Processor for every task.
        String writtenDot = Files.readString(output);
        assertTrue(writtenDot.contains("Start="), writtenDot);
        assertTrue(writtenDot.contains("Processor="), writtenDot);

        // And it should still be valid DOT that parses back into the same two-task graph.
        TaskGraph reparsed = new DotParser().parse(output);
        assertEquals(2, reparsed.taskCount());
        assertEquals(1, reparsed.edgeCount());
    }

    @Test
    @DisplayName("-p 2 solves on two threads and writes to the default name, INPUT-output.dot")
    void parallelRunWritesScheduleToDefaultOutput(@TempDir Path directory) throws IOException {
        Path input = writeInput(directory);

        Main.main(new String[] {input.toString(), "2", "-p", "2"});

        Path output = directory.resolve("tiny-output.dot");
        assertTrue(Files.exists(output), "the default output file was not written");

        TaskGraph reparsed = new DotParser().parse(output);
        assertEquals(2, reparsed.taskCount());
    }
}
