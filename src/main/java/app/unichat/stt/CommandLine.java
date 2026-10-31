package app.unichat.stt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The user's own transcription command ("Comando propio"): split like a shell would (quotes group words, no other
 * shell features), then placeholders replaced per word, so a path with spaces stays one argument. Pure, tested.
 */
public final class CommandLine {
    private CommandLine() {}

    /**
     * Words of {@code line}; "double" or 'single' quotes keep spaces. Backslashes are literal (Windows paths), except
     * {@code \"} inside double quotes.
     */
    public static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else if (c == '\\' && quote == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') cur.append(line.charAt(++i));
                else cur.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) out.add(cur.toString());
                cur.setLength(0);
                inWord = false;
            } else {
                cur.append(c);
                inWord = true;
            }
        }
        if (inWord) out.add(cur.toString());
        return out;
    }

    /** {@link #split} with every {@code {name}} of {@code vars} replaced inside each word. */
    public static List<String> expand(String template, Map<String, String> vars) {
        List<String> out = new ArrayList<>();
        for (String word : split(template)) {
            for (Map.Entry<String, String> v : vars.entrySet()) word = word.replace("{" + v.getKey() + "}", v.getValue());
            out.add(word);
        }
        return out;
    }
}
