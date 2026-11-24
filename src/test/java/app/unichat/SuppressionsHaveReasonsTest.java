package app.unichat;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private static final Pattern WARNINGS = Pattern.compile("@\\s*(?:java\\.lang\\.)?" + "SuppressWarnings\\s*\\(");
    private static final Pattern FB_WARNINGS = fbWarnings();
    private static final Pattern JUSTIFIED = Pattern.compile("justification\\s*=\\s*\"[^\"]*\\S[^\"]*\"");

    @SuppressFBWarnings(value = "REDOS", justification = "reads the repo's own sources; the optional prefix ends at a literal dot")
    private static Pattern fbWarnings() {
        return Pattern.compile("@\\s*(?:[\\w.]+\\.)?" + "SuppressFBWarnings\\b");
    }

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
    void theWaysAroundItAreFoundToo() {
        assertEquals(1, unexplained("A.java", "/* a */ int a = 1; // " + NOPMD + "\n").size(), "code after a closed /* */");
        assertEquals(1, unexplained("A.java", "@java.lang." + "SuppressWarnings(\"x\")\n").size(), "qualified");
        assertEquals(1, unexplained("A.java", "@" + "SuppressFBWarnings\nvoid f() {}\n").size(), "SpotBugs' with no arguments");
        assertEquals(1, unexplained("A.java", "@" + "SuppressWarnings({\"PMD.A\", // why\n    \"PMD.B\"})\n").size(),
                "a list over lines: the reason goes on the line it closes");
        assertEquals(1, unexplained("A.java", "@" + "SuppressWarnings(\"PMD\") // why\n").size(), "every PMD rule at once");
        assertEquals(1, unexplained("A.java", "@" + "SuppressWarnings({\"all\"}) // why\n").size(), "every warning at once");
        assertEquals(1, unexplained("A.java", "@" + "SuppressWarnings(\"checkstyle:all\") // why\n").size(), "every Checkstyle check");
        assertEquals(1, unexplained("A.java", "@" + "SuppressWarnings(\"checkstyle\") // why\n").size(), "every Checkstyle check");
        assertEquals(1, unexplained("A.java", "String s = \"\"\"\n  text\n  \"\"\"; @" + "SuppressWarnings(\"x\") int b;\n").size(),
                "after a text block");
    }

    @Test
    void spotBugsSuppressionsSitOnOneMemberNeverAType() {
        String ok = "@" + "SuppressFBWarnings(value = \"X\", justification = \"why\")";
        for (String type : List.of("class A {}", "public static final class A extends B {}", "interface A {}", "enum A { B }",
                "record A(int b) {}", "@interface A {}", "@Structure.FieldOrder({\"a\"})\n@Deprecated public final class A {}")) {
            assertEquals(1, unexplained("A.java", ok + "\n" + type + "\n").size(), "on a type: " + type);
        }
        for (String member : List.of("void f() {}", "@Override\npublic int f(int a) { return a; }", "private int a;",
                "static final Pattern P = p();", "A(String b) {}", "<T> T f(Class<T> c) { return null; }",
                "public WString lpszClassName;")) {
            assertEquals(List.of(), unexplained("A.java", ok + "\n" + member + "\n"), "on a member: " + member);
        }
    }

    @Test
    void checkstyleSuppressionsSitOnOneMemberNeverTheFilesType() {
        for (String name : List.of("checkstyle:MethodName", "HiddenField", "CHECKSTYLE:hiddenfield")) {
            String ok = "@" + "SuppressWarnings(\"" + name + "\") // why\n";
            assertEquals(1, unexplained("A.java", ok + "public final class A {}\n").size(), name + " on the file's class");
            assertEquals(1, unexplained("A.java", "package a;\n" + ok + "@Deprecated\ninterface A {}\n").size(), name + " on its interface");
            assertEquals(List.of(), unexplained("A.java", "class A {\n" + ok + "    interface B {}\n}\n"), name + " on a nested type");
            assertEquals(List.of(), unexplained("A.java", "class A {\n" + ok + "    void f(int a) {}\n}\n"), name + " on a method");
        }
        assertEquals(List.of(), unexplained("A.java", "@" + "SuppressWarnings(\"PMD.TooManyMethods\") // why\nclass A {}\n"),
                "another tool's on a class is that tool's rule");
    }

    @Test
    void aSuppressionWithItsReasonPasses() {
        assertEquals(List.of(), unexplained("A.java", "int a = 1; // " + NOPMD + ": UnusedAssignment, read by the listener\n"));
        assertEquals(List.of(), unexplained("A.java", "@" + "SuppressWarnings(\"unchecked\") // the map holds only lists\n"));
        assertEquals(List.of(), unexplained("A.java", "@" + "SuppressWarnings(\"x\") List<String> l = get(); // why\n"));
        assertEquals(List.of(), unexplained("A.java", "@" + "SuppressFBWarnings(value = \"X\", justification = \"why\")\n"));
        assertTrue(unexplained("A.java", "/** Use @" + "SuppressWarnings with care. */\n").isEmpty(),
                "a word in a comment isn't an annotation");
        assertTrue(unexplained("A.java", "/*\n * Not here: // " + NOPMD + "\n * @" + "SuppressFBWarnings\n */\n").isEmpty(),
                "nor inside a block comment over lines");
        assertTrue(unexplained("A.java", "String s = \"// " + NOPMD + " @" + "SuppressWarnings(x)\";\n").isEmpty(), "nor in a string");
        assertEquals(List.of(), unexplained("A.java", "@" + "SuppressWarnings({\"PMD.A\",\n    \"PMD.B\"}) // why, for both\n"));
    }

    /**
     * The suppressions in {@code text} (file {@code name}) that don't say why, as "name:line: the line". A NOPMD is read
     * in a line comment only; an annotation (qualified or not) in code only, never in a comment or a string. A
     * {@code @SuppressWarnings} that spans lines has its reason on the line it closes; one naming "PMD" or "all"
     * (every rule at once) never passes.
     */
    static List<String> unexplained(String name, String text) {
        String kinds = kinds(text);
        StringBuilder blanked = new StringBuilder(text);
        for (int k = 0; k < text.length(); k++) if (kinds.charAt(k) != CODE && text.charAt(k) != '\n') blanked.setCharAt(k, ' ');
        String code = blanked.toString();
        String[] lines = text.split("\n", -1);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String comment = lineComment(text, kinds, i);
            if (comment.contains(NOPMD) && !comment.matches(".*" + NOPMD + ":\\s*\\S.*")) out.add(at(name, lines, i));
        }
        Matcher w = WARNINGS.matcher(code);
        while (w.find()) {
            int close = closing(code, w.end() - 1);
            String args = text.substring(w.end(), close);
            boolean blanket = args.matches("(?s).*\"\\s*(PMD|all|checkstyle|checkstyle:all)\\s*\".*");
            boolean wholeFile = namesCheckstyle(args) && depth(code, w.start()) == 0
                    && TYPE.matcher(declarationAfter(code, close + 1)).find();
            if (blanket || wholeFile || !lineComment(text, kinds, lineOf(text, close)).matches("//\\s*\\S.*")) {
                out.add(at(name, lines, lineOf(text, w.start())));
            }
        }
        Matcher fb = FB_WARNINGS.matcher(code);
        while (fb.find()) {
            int open = skipBlanks(code, fb.end());
            String inside = open < code.length() && code.charAt(open) == '(' ? text.substring(open, closing(code, open) + 1) : "";
            int after = inside.isEmpty() ? fb.end() : open + inside.length();
            if (!JUSTIFIED.matcher(inside).find() || TYPE.matcher(declarationAfter(code, after)).find()) {
                out.add(at(name, lines, lineOf(text, fb.start())));
            }
        }
        return out;
    }

    private static final char CODE = 'c';
    private static final char TEXT = 's';
    private static final char BLOCK = 'b';
    private static final char LINE = 'l';

    /** For each character of {@code text}: code, inside a string, char or text block, or in a block or line comment. */
    private static String kinds(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int end;
            char kind;
            if (text.startsWith("/*", i)) {
                end = text.indexOf("*/", i + 2) < 0 ? text.length() : text.indexOf("*/", i + 2) + 2;
                kind = BLOCK;
            } else if (text.startsWith("//", i)) {
                end = text.indexOf('\n', i) < 0 ? text.length() : text.indexOf('\n', i);
                kind = LINE;
            } else if (text.startsWith("\"\"\"", i)) {
                end = text.indexOf("\"\"\"", i + 3) < 0 ? text.length() : text.indexOf("\"\"\"", i + 3) + 3;
                kind = TEXT;
            } else if (text.charAt(i) == '"' || text.charAt(i) == '\'') {
                end = quoted(text, i);
                kind = TEXT;
            } else {
                end = i + 1;
                kind = CODE;
            }
            for (int k = i; k < end; k++) out.append(kind);
            i = end;
        }
        return out.toString();
    }

    /** Where the string or char starting at {@code i} ends: just past its closing quote, or at the line's end. */
    private static int quoted(String text, int i) {
        char q = text.charAt(i);
        for (int k = i + 1; k < text.length(); k++) {
            if (text.charAt(k) == '\\') k++;
            else if (text.charAt(k) == q) return k + 1;
            else if (text.charAt(k) == '\n') return k;
        }
        return text.length();
    }

    /** Line {@code line}'s {@code //} comment, or "" when it has none. */
    private static String lineComment(String text, String kinds, int line) {
        int start = 0;
        for (int n = 0; n < line; n++) start = text.indexOf('\n', start) + 1;
        int end = text.indexOf('\n', start) < 0 ? text.length() : text.indexOf('\n', start);
        int at = kinds.indexOf(LINE, start);
        return at < 0 || at >= end ? "" : text.substring(at, end);
    }

    /** The parenthesis that closes the one at {@code open} in code. */
    private static int closing(String code, int open) {
        int depth = 0;
        for (int k = open; k < code.length(); k++) {
            if (code.charAt(k) == '(') depth++;
            else if (code.charAt(k) == ')' && --depth == 0) return k;
        }
        return code.length() - 1;
    }

    /** Checkstyle's checks, by the names its SuppressWarningsHolder takes: "checkstyle:" and the check, any case. */
    private static final List<String> CHECKSTYLE = checkstyleModules();
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

    private static List<String> checkstyleModules() {
        try {
            Matcher m = Pattern.compile("<module name=\"(\\w+)\"").matcher(Files.readString(Path.of("checkstyle.xml")));
            List<String> out = new ArrayList<>();
            while (m.find()) out.add(m.group(1).toLowerCase(Locale.ROOT));
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("checkstyle.xml can't be read", e);
        }
    }

    /** Whether the annotation's arguments name one of Checkstyle's checks. */
    private static boolean namesCheckstyle(String args) {
        Matcher q = QUOTED.matcher(args);
        while (q.find()) {
            String name = q.group(1).strip().toLowerCase(Locale.ROOT);
            if (name.startsWith("checkstyle:") || CHECKSTYLE.contains(name)) return true;
        }
        return false;
    }

    /** How many braces are open in {@code code} (comments and strings blanked) before {@code index}: 0 is the file's top. */
    private static int depth(String code, int index) {
        int d = 0;
        for (int k = 0; k < index; k++) {
            if (code.charAt(k) == '{') d++;
            else if (code.charAt(k) == '}') d--;
        }
        return d;
    }

    /** A type's declaration: SpotBugs' suppression goes on the one member it's about, never a whole class. */
    private static final Pattern TYPE = Pattern.compile("(^|\\s)(class|interface|enum|record|@interface)\\s");

    /** What's declared after {@code from}, past any other annotations: up to its body, its parameters or its value. */
    private static String declarationAfter(String code, int from) {
        int k = skipBlanks(code, from);
        while (k < code.length() && code.charAt(k) == '@' && !code.startsWith("@interface", k)) {
            k++;
            while (k < code.length() && (Character.isJavaIdentifierPart(code.charAt(k)) || code.charAt(k) == '.')) k++;
            int open = skipBlanks(code, k);
            if (open < code.length() && code.charAt(open) == '(') k = closing(code, open) + 1;
            k = skipBlanks(code, k);
        }
        int end = k;
        while (end < code.length() && "{;=(".indexOf(code.charAt(end)) < 0) end++;
        return " " + code.substring(k, end) + " ";
    }

    private static int skipBlanks(String code, int from) {
        int k = from;
        while (k < code.length() && Character.isWhitespace(code.charAt(k))) k++;
        return k;
    }

    private static int lineOf(String text, int index) {
        return (int) text.substring(0, Math.min(index, text.length())).chars().filter(c -> c == '\n').count();
    }

    private static String at(String name, String[] lines, int i) {
        return name + ":" + (i + 1) + ": " + lines[i].strip();
    }
}
