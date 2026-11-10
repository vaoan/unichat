package app.unichat.claude;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Mistakes made once, as patterns that must not come back (`.claude/lessons.tsv`). Run by Claude Code's edit hook on
 * each file written ({@code --hook}: the path comes on stdin, a finding goes back to Claude with exit 2), by hand on
 * files given as arguments, and over the whole repository by {@code LessonsTest}. A line that has a reason to break
 * one says {@code lesson-ok: <id>} in a comment.
 */
public final class Lessons {
    /** One row of the table: which files, what must not appear, and why. */
    record Lesson(String id, Pattern files, Pattern text, String why) { }

    /** A line that breaks a lesson. */
    record Finding(String file, int line, Lesson lesson) {
        @Override
        public String toString() {
            return file + ":" + line + ": [" + lesson.id + "] " + lesson.why;
        }
    }

    private final Path root;
    private final List<Lesson> lessons = new ArrayList<>();

    Lessons(Path root) throws IOException {
        this.root = root;
        for (String[] row : HookInput.table(root.resolve(".claude/lessons.tsv"), 4)) {
            lessons.add(new Lesson(row[0], glob(row[1]), Pattern.compile(row[2]), row[3]));
        }
    }

    List<Lesson> all() {
        return lessons;
    }

    /** What {@code file} breaks; nothing for a file outside the repository, missing, or that no lesson is about. */
    List<Finding> check(Path file) throws IOException {
        Path abs = root.resolve(file).toAbsolutePath().normalize();
        if (!abs.startsWith(root) || !Files.isRegularFile(abs)) return List.of();
        String rel = root.relativize(abs).toString().replace('\\', '/');
        var applying = lessons.stream().filter(l -> l.files.matcher(rel).matches()).toList();
        if (applying.isEmpty()) return List.of();
        var found = new ArrayList<Finding>();
        List<String> lines = Files.readAllLines(abs, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (Lesson l : applying) {
                if (l.text.matcher(line).find() && !line.contains("lesson-ok: " + l.id)) found.add(new Finding(rel, i + 1, l));
            }
        }
        return found;
    }

    /** A glob over paths relative to the root, with `/`: `**` any folders, `*` within a name, `{a,b}` either. */
    static Pattern glob(String glob) {
        var re = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && glob.startsWith("**/", i)) {
                re.append("(?:.*/)?");
                i += 2;
            } else if (c == '*' && glob.startsWith("**", i)) {
                re.append(".*");
                i++;
            } else if (c == '*') {
                re.append("[^/]*");
            } else if (c == '?') {
                re.append("[^/]");
            } else if (c == '{') {
                re.append("(?:");
            } else if (c == '}') {
                re.append(')');
            } else if (c == ',') {
                re.append('|');
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString());
    }

    public static void main(String[] args) throws IOException {
        Path root = HookInput.projectDir();
        var lessons = new Lessons(root);
        var files = new ArrayList<String>();
        if (args.length == 1 && args[0].equals("--hook")) {
            String path = HookInput.string(HookInput.read(System.in), "file_path");
            if (path != null) files.add(path);
        } else {
            files.addAll(List.of(args));
        }
        var found = new ArrayList<Finding>();
        for (String f : files) found.addAll(lessons.check(Path.of(f)));
        if (found.isEmpty()) return;
        System.err.println("This breaks a lesson learned before (.claude/lessons.tsv). Fix it, or if this line has a real "
                + "reason, say so in a comment with \"lesson-ok: <id>\":");
        found.forEach(System.err::println);
        System.exit(2);
    }
}
