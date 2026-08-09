package se306.scheduler.cli;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link CliArguments} captures the command line from the project description:
 *
 * <pre>{@code
 * java -jar scheduler.jar INPUT.dot P [OPTION]
 *   INPUT.dot  a task graph with integer weights in dot format
 *   P          number of processors to schedule the INPUT graph on
 * Optional:
 *   -p N       use N cores for execution in parallel (default is sequential)
 *   -v         visualise the search
 *   -o OUTPUT  output file is named OUTPUT (default is INPUT-output.dot)
 * }</pre>
 *
 * <p>The JVM strips {@code java -jar scheduler.jar} before {@code main} runs, so each test parses a
 * variation of the arguments after the jar name — exactly the array {@code main} would receive —
 * and checks the stored values; the last ones check that argument lists not matching the usage are
 * rejected.
 */
class CliArgumentsTest {

    @Test
    @DisplayName("java -jar scheduler.jar INPUT.dot P — options take their defaults")
    void requiredArgumentsOnly() {
        CliArguments arguments = CliArguments.parse(new String[] {"INPUT.dot", "2"});

        assertEquals(Path.of("INPUT.dot"), arguments.inputFile());
        assertEquals(2, arguments.processorCount());
        assertEquals(1, arguments.coreCount());
        assertFalse(arguments.visualise());
        assertEquals(Path.of("INPUT-output.dot"), arguments.outputFile());
    }

    @Test
    @DisplayName("java -jar scheduler.jar INPUT.dot P -p N — N cores are stored")
    void parallelOption() {
        CliArguments arguments = CliArguments.parse(new String[] {"INPUT.dot", "4", "-p", "8"});

        assertEquals(8, arguments.coreCount());
    }

    @Test
    @DisplayName("java -jar scheduler.jar INPUT.dot P -v — visualisation is stored")
    void visualiseOption() {
        CliArguments arguments = CliArguments.parse(new String[] {"INPUT.dot", "4", "-v"});

        assertTrue(arguments.visualise());
    }

    @Test
    @DisplayName("java -jar scheduler.jar INPUT.dot P -o OUTPUT — output name is stored")
    void outputOption() {
        CliArguments arguments =
                CliArguments.parse(new String[] {"INPUT.dot", "4", "-o", "OUTPUT.dot"});

        assertEquals(Path.of("OUTPUT.dot"), arguments.outputFile());
    }

    @Test
    @DisplayName("-o OUTPUT without an extension gets .dot, since the output is always a DOT file")
    void outputOptionGainsDotExtension() {
        CliArguments arguments = CliArguments.parse(new String[] {"INPUT.dot", "4", "-o", "output"});

        assertEquals(Path.of("output.dot"), arguments.outputFile());
    }

    @Test
    @DisplayName("-o OUTPUT that already ends in .dot is left alone, whatever its case")
    void outputOptionKeepsExistingDotExtension() {
        assertEquals(Path.of("output.dot"),
                CliArguments.parse(new String[] {"INPUT.dot", "4", "-o", "output.dot"})
                        .outputFile());
        assertEquals(Path.of("output.DOT"),
                CliArguments.parse(new String[] {"INPUT.dot", "4", "-o", "output.DOT"})
                        .outputFile());
    }

    @Test
    @DisplayName("-o keeps the directory it was given, extension or not")
    void outputOptionKeepsDirectory() {
        assertEquals(Path.of("results", "schedule.dot"),
                CliArguments.parse(new String[] {"INPUT.dot", "4", "-o", "results/schedule"})
                        .outputFile());
    }

    @Test
    @DisplayName("all options together, in any order")
    void allOptions() {
        CliArguments arguments = CliArguments
                .parse(new String[] {"INPUT.dot", "4", "-o", "OUTPUT.dot", "-v", "-p", "8"});

        assertEquals(Path.of("INPUT.dot"), arguments.inputFile());
        assertEquals(4, arguments.processorCount());
        assertEquals(8, arguments.coreCount());
        assertTrue(arguments.visualise());
        assertEquals(Path.of("OUTPUT.dot"), arguments.outputFile());
    }

    @Test
    @DisplayName("default output name keeps the input's directory")
    void defaultOutputKeepsDirectory() {
        CliArguments arguments = CliArguments.parse(new String[] {"graphs/INPUT.dot", "2"});

        assertEquals(Path.of("graphs", "INPUT-output.dot"), arguments.outputFile());
    }

    @Test
    @DisplayName("missing arguments are rejected")
    void missingArguments() {
        assertThrows(CliArgumentException.class, () -> CliArguments.parse(new String[] {}));
        assertThrows(CliArgumentException.class,
                () -> CliArguments.parse(new String[] {"INPUT.dot"}));
    }

    @Test
    @DisplayName("P and N must be positive integers")
    void invalidCounts() {
        assertThrows(CliArgumentException.class,
                () -> CliArguments.parse(new String[] {"INPUT.dot", "two"}));
        assertThrows(CliArgumentException.class,
                () -> CliArguments.parse(new String[] {"INPUT.dot", "0"}));
        assertThrows(CliArgumentException.class,
                () -> CliArguments.parse(new String[] {"INPUT.dot", "2", "-p", "0"}));
    }

    @Test
    @DisplayName("an option missing its value is rejected")
    void optionMissingValue() {
        assertThrows(CliArgumentException.class,
                () -> CliArguments.parse(new String[] {"INPUT.dot", "2", "-p"}));
        assertThrows(CliArgumentException.class,
                () -> CliArguments.parse(new String[] {"INPUT.dot", "2", "-o"}));
    }

    @Test
    @DisplayName("an unknown option is rejected")
    void unknownOption() {
        assertThrows(CliArgumentException.class,
                () -> CliArguments.parse(new String[] {"INPUT.dot", "2", "-x"}));
    }
}