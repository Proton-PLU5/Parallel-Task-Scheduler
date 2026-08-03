package se306.scheduler.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import se306.scheduler.graph.GraphBuilder;
import se306.scheduler.graph.TaskGraph;

/**
 * Reads a task graph in DOT format (WBS 2.2).
 *
 * <p>The accepted format is narrow — node declarations and edges carrying an integer {@code Weight}
 * — so the parser is hand-rolled rather than pulling in a general DOT grammar. Input is split into
 * statements (respecting quotes, bracketed attribute lists and comments), and each statement is
 * matched as an edge first, then as a node. <strong>Anything else is skipped silently</strong>:
 * graph-level attributes, {@code node}/{@code edge} defaults, comments and subgraph braces must all
 * be ignored gracefully.
 *
 * <p>Node names may be arbitrary strings and may appear in any order — an edge may reference a task
 * whose declaration comes later — so resolution and validation are deferred to
 * {@link GraphBuilder#build()}.
 */
public final class DotParser {

    /** DOT keywords that introduce a statement we do not care about. */
    private static final Set<String> IGNORED_KEYWORDS =
            Set.of("digraph", "graph", "subgraph", "strict", "node", "edge");

    /** Pulls the raw text of the {@code Weight} attribute out of an attribute list. */
    private static final Pattern WEIGHT_ATTRIBUTE =
            Pattern.compile("(?i)(?:^|[,;\\s])\\s*Weight\\s*=\\s*([^,;\\]]+)");

    /** A statement of input, together with the line it started on, for error reporting. */
    private record Statement(String text, int line) { }

    /** Parses the DOT file at {@code path}. */
    public TaskGraph parse(Path path) throws IOException {
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    /** Parses DOT source held in memory. */
    public TaskGraph parse(String source) {
        GraphBuilder builder = new GraphBuilder();
        for (Statement statement : split(stripComments(source))) {
            parseStatement(statement, builder);
        }
        return builder.build();
    }

    private void parseStatement(Statement statement, GraphBuilder builder) {
        String text = statement.text();
        String keyword = firstWord(text).toLowerCase(Locale.ROOT);
        if (IGNORED_KEYWORDS.contains(keyword)) {
            // The graph header carries the name we need to reproduce on output; the rest
            // ("graph [...]" defaults, node/edge defaults) is deliberately dropped.
            if (keyword.equals("digraph") || keyword.equals("strict")) {
                readGraphName(text, builder);
            }
            return;
        }

        int attributeStart = indexOfTopLevel(text, '[');
        String target = attributeStart < 0 ? text : text.substring(0, attributeStart);
        String attributes = null;
        if (attributeStart >= 0) {
            int end = text.lastIndexOf(']');
            attributes = end > attributeStart ? text.substring(attributeStart + 1, end)
                    : text.substring(attributeStart + 1);
        }

        List<String> endpoints = splitOnArrows(target);
        if (endpoints.size() > 1) {
            // A chain "a -> b -> c" declares an edge between each consecutive pair.
            for (int i = 0; i + 1 < endpoints.size(); i++) {
                String from = requireName(endpoints.get(i), statement.line());
                String to = requireName(endpoints.get(i + 1), statement.line());
                int weight = requireWeight(attributes, "edge '" + from + " -> " + to + "'",
                        statement.line());
                builder.addEdge(unquote(from), unquote(to), weight);
            }
            return;
        }

        String name = target.trim();
        if (name.isEmpty()) {
            return;
        }
        if (attributes == null) {
            // A bare "a;" only mentions a task, it does not declare its execution time. Skip it;
            // if nothing ever gives it a weight, build() reports it as an undeclared endpoint.
            return;
        }
        int weight = requireWeight(attributes, "task '" + name + "'", statement.line());
        builder.addNode(unquote(name), weight);
    }

    /** Reads the name from a {@code digraph "example" {} header, if it has one. */
    private void readGraphName(String header, GraphBuilder builder) {
        String rest = header.trim();
        // Drop the leading keywords ("strict digraph", "digraph", ...) to leave just the name.
        while (!rest.isEmpty() && IGNORED_KEYWORDS.contains(firstWord(rest).toLowerCase(Locale.ROOT))) {
            rest = rest.substring(firstWord(rest).length()).trim();
        }
        // An anonymous "digraph {" leaves nothing, and a stray attribute list is not a name.
        if (!rest.isEmpty() && rest.charAt(0) != '[') {
            builder.graphName(unquote(rest));
        }
    }

    private static String requireName(String raw, int line) {
        String name = raw.trim();
        if (name.isEmpty()) {
            throw new DotParseException(line, "edge is missing a task name on one side of '->'.");
        }
        return name;
    }

    /**
     * Extracts the integer {@code Weight} from an attribute list, failing with a message that names
     * what the weight belongs to.
     */
    private static int requireWeight(String attributes, String owner, int line) {
        if (attributes == null) {
            throw new DotParseException(line, owner + " has no attributes, so no Weight. "
                    + "Every task and edge needs an integer Weight.");
        }
        Matcher matcher = WEIGHT_ATTRIBUTE.matcher(attributes);
        if (!matcher.find()) {
            throw new DotParseException(line, owner + " is missing a Weight attribute.");
        }
        String raw = unquote(matcher.group(1).trim());
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new DotParseException(line,
                    owner + " has non-integer Weight '" + raw + "'. All weights must be integers.");
        }
    }

    /**
     * Replaces DOT comments with spaces, preserving newlines so reported line numbers stay accurate.
     * Handles {@code //}, {@code /* *}{@code /} and lines beginning with {@code #}.
     */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inQuote = false;
        boolean escaped = false;
        boolean atLineStart = true;
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);

            if (inQuote) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inQuote = false;
                }
                i++;
                continue;
            }

            if (c == '"') {
                inQuote = true;
                atLineStart = false;
                out.append(c);
                i++;
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                i = blankToEndOfLine(source, i, out);
                atLineStart = true;
                continue;
            }
            if (c == '#' && atLineStart) {
                i = blankToEndOfLine(source, i, out);
                atLineStart = true;
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                out.append("  ");
                i += 2;
                while (i < source.length()
                        && !(source.charAt(i) == '*' && i + 1 < source.length()
                             && source.charAt(i + 1) == '/')) {
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < source.length()) {
                    out.append("  ");
                    i += 2;
                }
                atLineStart = false;
                continue;
            }

            if (c == '\n') {
                atLineStart = true;
            } else if (!Character.isWhitespace(c)) {
                atLineStart = false;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static int blankToEndOfLine(String source, int i, StringBuilder out) {
        while (i < source.length() && source.charAt(i) != '\n') {
            out.append(' ');
            i++;
        }
        if (i < source.length()) {
            out.append('\n');
            i++;
        }
        return i;
    }

    /**
     * Splits source into statements on {@code ;}, {@code &#123;} and {@code &#125;}, and on newlines
     * — but only outside quotes and outside a bracketed attribute list, so a declaration may span
     * several lines and several declarations may share one line.
     */
    static List<Statement> split(String source) {
        List<Statement> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int line = 1;
        int startLine = 1;
        int depth = 0;
        boolean inQuote = false;
        boolean escaped = false;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);

            if (inQuote) {
                current.append(c);
                if (c == '\n') {
                    line++;
                }
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inQuote = false;
                }
                continue;
            }

            switch (c) {
                case '"' -> {
                    inQuote = true;
                    current.append(c);
                }
                case '[' -> {
                    depth++;
                    current.append(c);
                }
                case ']' -> {
                    if (depth > 0) {
                        depth--;
                    }
                    current.append(c);
                }
                case ';', '{', '}' -> {
                    if (depth == 0) {
                        startLine = flush(statements, current, startLine, line);
                    } else {
                        current.append(c);
                    }
                }
                case '\n' -> {
                    if (depth == 0) {
                        startLine = flush(statements, current, startLine, line + 1);
                    } else {
                        current.append(' ');
                    }
                    line++;
                }
                case '\r' -> { /* handled with the following \n */ }
                default -> current.append(c);
            }
        }
        flush(statements, current, startLine, line);
        return statements;
    }

    /**
     * Emits the buffered statement if it is non-blank and resets the buffer.
     *
     * @return the line the next statement starts on
     */
    private static int flush(List<Statement> statements, StringBuilder current, int startLine,
                             int nextLine) {
        String text = current.toString().trim();
        if (!text.isEmpty()) {
            statements.add(new Statement(text, startLine));
        }
        current.setLength(0);
        return nextLine;
    }

    /** Index of the first {@code needle} that is not inside a quoted string, or -1. */
    private static int indexOfTopLevel(String text, char needle) {
        boolean inQuote = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuote) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inQuote = false;
                }
            } else if (c == '"') {
                inQuote = true;
            } else if (c == needle) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Splits an edge target on {@code ->} occurrences outside quotes. Returns a single element when
     * there is no arrow, i.e. the statement is a node declaration.
     */
    private static List<String> splitOnArrows(String text) {
        List<String> parts = new ArrayList<>();
        boolean inQuote = false;
        boolean escaped = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuote) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inQuote = false;
                }
                continue;
            }
            if (c == '"') {
                inQuote = true;
            } else if (c == '-' && i + 1 < text.length() && text.charAt(i + 1) == '>') {
                parts.add(text.substring(start, i));
                i++;
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private static String firstWord(String text) {
        String trimmed = text.trim();
        int end = 0;
        while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))
                && trimmed.charAt(end) != '"' && trimmed.charAt(end) != '[') {
            end++;
        }
        return trimmed.substring(0, end);
    }

    private static boolean isQuoted(String token) {
        String t = token.trim();
        return t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"';
    }

    /** Strips surrounding quotes and unescapes the contents, giving the task's internal identity. */
    static String unquote(String token) {
        String t = token.trim();
        if (!isQuoted(t)) {
            return t;
        }
        String body = t.substring(1, t.length() - 1);
        StringBuilder out = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                out.append(body.charAt(++i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}