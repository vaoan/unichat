package app.unichat.claude;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What Claude Code hands a hook on stdin (JSON), and the tables of rules the hooks read. No JSON library: the hooks
 * run as plain source files (`java File.java`), so they read the one string field each of them needs.
 */
final class HookInput {
    private HookInput() { }

    static String read(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    /** The first string value of {@code key} anywhere in {@code json}, unescaped; null when there is none. */
    static String string(String json, String key) {
        var m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"").matcher(json);
        if (!m.find()) return null;
        var out = new StringBuilder();
        for (int i = m.end(); i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') return out.toString();
            if (c != '\\' || i + 1 >= json.length()) {
                out.append(c);
                continue;
            }
            char e = json.charAt(++i);
            switch (e) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    out.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> out.append(e);
            }
        }
        return null;
    }

    /** The project's root: Claude Code's {@code CLAUDE_PROJECT_DIR}, else the working directory. */
    static Path projectDir() {
        String dir = System.getenv("CLAUDE_PROJECT_DIR");
        return Path.of(dir == null || dir.isBlank() ? "." : dir).toAbsolutePath().normalize();
    }

    /** The rows of a tab-separated table, without blank lines and {@code #} comments; each row has {@code columns}. */
    static List<String[]> table(Path file, int columns) throws IOException {
        var rows = new ArrayList<String[]>();
        int n = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            n++;
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] row = line.split("\t", -1);
            if (row.length != columns) {
                throw new IllegalArgumentException(file + ":" + n + ": " + columns + " columns separated by tabs expected, "
                        + row.length + " found");
            }
            rows.add(row);
        }
        return rows;
    }
}
