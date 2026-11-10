package app.unichat.claude;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Shell commands Claude must not run here, or must ask first (`.claude/guards.tsv`): Claude Code's hook before each
 * Bash command. A denied command goes back to Claude with the reason (exit 2); "ask" leaves it to the person.
 */
public final class Guard {
    /** One row: a command matching {@code when} and not {@code unless} gets {@code decision}, for {@code why}. */
    record Rule(String id, Pattern when, Pattern unless, String decision, String why) { }

    private final List<Rule> rules = new ArrayList<>();

    Guard(Path root) throws IOException {
        for (String[] row : HookInput.table(root.resolve(".claude/guards.tsv"), 5)) {
            if (!row[3].equals("deny") && !row[3].equals("ask")) {
                throw new IllegalArgumentException("guards.tsv: decision must be deny or ask, not " + row[3]);
            }
            rules.add(new Rule(row[0], Pattern.compile(row[1]), row[2].isEmpty() ? null : Pattern.compile(row[2]),
                    row[3], row[4]));
        }
    }

    List<Rule> all() {
        return rules;
    }

    /** The first rule {@code command} falls under, or null when it may run. */
    Rule judge(String command) {
        for (Rule r : rules) {
            if (r.when.matcher(command).find() && (r.unless == null || !r.unless.matcher(command).find())) return r;
        }
        return null;
    }

    public static void main(String[] args) throws IOException {
        String command = HookInput.string(HookInput.read(System.in), "command");
        Rule rule = command == null ? null : new Guard(HookInput.projectDir()).judge(command);
        if (rule == null) return;
        String why = "[" + rule.id + "] " + rule.why + " (.claude/guards.tsv)";
        if (rule.decision.equals("deny")) {
            System.err.println(why);
            System.exit(2);
        }
        System.out.println("{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"ask\","
                + "\"permissionDecisionReason\":\"" + why.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}}");
    }
}
