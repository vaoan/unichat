package app.unichat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every check turned off for one line or member says why, on that line: {@code // NOPMD: why},
 * {@code @SuppressWarnings("x") // why}, or SpotBugs' {@code justification}. A suppression without one hides a finding
 * nobody decided on.
 */
class SuppressionsHaveReasonsTest {
    // Built from parts, so this file's own text doesn't match them.
    private static final String NOPMD = "NO" + "PMD";
    private static final Pattern WARNINGS = Pattern.compile("@" + "SuppressWarnings\\s*\\(");
    private static final Pattern FB_WARNINGS = Pattern.compile("@" + "SuppressFBWarnings\\s*\\(([^)]*)\\)");
    private static final Pattern JUSTIFIED = Pattern.compile("justification\\s*=\\s*\"[^\"]*\\S[^\"]*\"");

    @Test
    void everySuppressionInTheSourcesSaysWhy() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path root : List.of(Path.of("src/main/java"), Path.of("src/test/java"))) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    found.addAll(unexplained(f.toString(), Files.readString(f)));
                }
            }
        }
        assertEquals(List.of(), found, "a suppression says why on its own line: // " + NOPMD + ": why, or the annotation, then // why");
    }

    @Test
    void aSuppressionWithoutItsReasonIsFound() {
        assertEquals(1, unexplained("A.java", "int a = 1; // " + NOPMD + "\n").size(), "no colon, no reason");
        assertEquals(1, unexplained("A.java", "int a = 1; // " + NOPMD + ":   \n").size(), "a colon, nothing after it");
        assertEquals(1, unexplained("A.java", "@" + "SuppressWarnings(\"unchecked\")\n").size(), "no comment");
        assertEquals(1, unexplained("A.java", "@" + "SuppressWarnings(\"x\") //\n").size(), "an empty comment");
        assertEquals(1, unexplained("A.java", "@" + "SuppressWarnings(\"x\") String s = \"// in a string\";\n").size(),
                "a // inside a string isn't a comment");
        assertEquals(1, unexplained("A.java", "@" + "SuppressFBWarnings(value = \"X\")\n").size(), "no justification");
        assertEquals(1, unexplained("A.java", "@" + "SuppressFBWarnings(value = \"X\", justification = \" \")\n").size(),
                "a blank justification");
    }

    @Test
    void aSuppressionWithItsReasonPasses() {
        assertEquals(List.of(), unexplained("A.java", "int a = 1; // " + NOPMD + ": UnusedAssignment, read by the listener\n"));
        assertEquals(List.of(), unexplained("A.java", "@" + "SuppressWarnings(\"unchecked\") // the map holds only lists\n"));
        assertEquals(List.of(), unexplained("A.java", "@" + "SuppressWarnings(\"x\") List<String> l = get(); // why\n"));
        assertEquals(List.of(), unexplained("A.java", "@" + "SuppressFBWarnings(value = \"X\", justification = \"why\")\n"));
        assertTrue(unexplained("A.java", "/** Use @" + "SuppressWarnings with care. */\n").isEmpty(),
                "a word in a comment isn't an annotation");
    }

    /** The suppressions in {@code text} (file {@code name}) that don't say why, as "name:line: the line". */
    static List<String> unexplained(String name, String text) {
        List<String> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.stripLeading();
            if (t.startsWith("*") || t.startsWith("/*")) continue; // a block comment: no suppression there
            String code = code(line);
            String comment = line.substring(code.length());
            boolean pmd = comment.contains(NOPMD) && !comment.matches(".*" + NOPMD + ":\\s*\\S.*");
            boolean warnings = WARNINGS.matcher(code).find() && !comment.matches("//\\s*\\S.*");
            boolean bad = pmd || warnings;
            if (bad) out.add(name + ":" + (i + 1) + ": " + line.strip());
        }
        Matcher fb = FB_WARNINGS.matcher(text);
        while (fb.find()) {
            if (!JUSTIFIED.matcher(fb.group(1)).find()) {
                out.add(name + ":" + text.substring(0, fb.start()).split("\n", -1).length + ": " + fb.group());
            }
        }
        return out;
    }

    /** {@code line} up to its {@code //} comment (strings and chars skipped); the whole line when it has none. */
    private static String code(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                return line.substring(0, i);
            }
        }
        return line;
    }
}
