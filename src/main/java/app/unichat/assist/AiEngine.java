package app.unichat.assist;

import app.unichat.support.BundledTools;
import app.unichat.support.Os;
import app.unichat.support.Processes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The AI command-line programs a reply can come from, already installed and signed in by the person.
 *
 * <p>The chat they read is written by other people, so it can carry instructions: {@link #command} gives them as little as
 * each program allows. Claude Code only gets to read the chosen folder (tested: outside it, reading is denied). Codex's
 * read-only sandbox can't write or use the network, but reads the whole disk; nothing narrower exists.
 */
public enum AiEngine {
    CLAUDE("Claude Code", "claude"),
    CODEX("Codex", "codex");

    private final String label;
    private final String exe;

    AiEngine(String label, String exe) {
        this.label = label;
        this.exe = exe;
    }

    public String label() { return label; }

    /** The arguments of one reply; the prompt goes through stdin. {@code folder} may be null; {@code reply}: Codex's output file. */
    List<String> command(Path bin, Path folder, Path workDir, Path reply) {
        return command(bin, folder, workDir, reply, false);
    }

    /**
     * {@code web}: the person asked for something to be looked up ([gaps] in what they wrote), so the program may search
     * the web. Searching only: opening any page would let a message in the chat send data out through its address.
     */
    List<String> command(Path bin, Path folder, Path workDir, Path reply, boolean web) {
        List<String> c = new ArrayList<>(List.of(bin.toString()));
        if (this == CLAUDE) {
            String tools = String.join(",", folder == null ? List.of() : List.of("Read", "Glob", "Grep"));
            if (web) tools = tools.isEmpty() ? "WebSearch" : tools + ",WebSearch";
            c.addAll(List.of("-p", "--no-session-persistence", "--permission-mode", "dontAsk", "--strict-mcp-config",
                    "--setting-sources", "", "--disable-slash-commands", "--tools", tools));
            List<String> allowed = new ArrayList<>();
            if (folder != null) {
                String all = claudePath(folder) + "/**";
                allowed.addAll(List.of("Read(" + all + ")", "Glob(" + all + ")", "Grep(" + all + ")"));
            }
            if (web) allowed.add("WebSearch");
            if (!allowed.isEmpty()) {
                c.add("--allowedTools");
                c.addAll(allowed);
            }
        } else {
            if (web) c.add("--search");
            // Like Claude's: none of the person's config (its MCP servers run outside the sandbox), exec rules or the
            // folder's AGENTS.md, which a message could turn to its ends.
            c.addAll(List.of("exec", "--sandbox", "read-only", "--ignore-user-config", "--ignore-rules",
                    "-c", "project_doc_max_bytes=0", "--skip-git-repo-check", "--ephemeral",
                    "-C", workDir.toString(), "-o", reply.toString(), "-"));
        }
        return c;
    }

    /** An absolute path as Claude Code's permission rules write it: {@code //Users/ana} or {@code //c/Users/ana}. */
    static String claudePath(Path folder) {
        String p = folder.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.length() > 1 && p.charAt(1) == ':') return "//" + Character.toLowerCase(p.charAt(0)) + p.substring(2);
        return "/" + p;
    }

    /** Where it's installed, or null. */
    public Path find() {
        for (String name : names()) {
            Path f = BundledTools.onPath(name);
            if (f != null) return f;
            for (Path d : usualFolders()) {
                if (Files.isExecutable(d.resolve(name))) return d.resolve(name);
            }
        }
        return Os.windows() ? null : fromLoginShell();
    }

    private List<String> names() {
        return Os.windows() ? List.of(exe + ".exe", exe + ".cmd") : List.of(exe);
    }

    /** A Mac app opened from Finder doesn't get the shell's PATH. */
    static List<Path> usualFolders() {
        Path home = Path.of(System.getProperty("user.home"));
        if (Os.windows()) {
            String appData = System.getenv("APPDATA");
            return appData == null ? List.of(home.resolve(".local/bin")) : List.of(home.resolve(".local/bin"), Path.of(appData, "npm"));
        }
        return List.of(Path.of("/opt/homebrew/bin"), Path.of("/usr/local/bin"), home.resolve(".local/bin"),
                home.resolve(".npm-global/bin"), home.resolve(".claude/local"), home.resolve(".bun/bin"));
    }

    private Path fromLoginShell() {
        try {
            Processes.Result r = Processes.capture(List.of(System.getenv().getOrDefault("SHELL", "/bin/sh"), "-lc", "command -v " + exe), 5);
            if (!r.finished()) return null;
            String out = r.out().strip();
            return out.startsWith("/") && Files.isExecutable(Path.of(out)) ? Path.of(out) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
