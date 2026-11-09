package app.unichat.assist;

import app.unichat.settings.Settings;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What's known of how the person writes ({@code writing-style.md}, which travels with them) and the corrections to
 * drafts not learned from yet ({@code writing-style-corrections.json}, this computer's; the last {@link #MAX_PENDING}).
 */
public final class WritingStyle {
    static final int MAX_PENDING = 20;
    private static final int MAX_AI_TEXTS = 200;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static WritingStyle instance;

    private final Path styleFile;
    private final Path correctionsFile;
    private final List<StylePrompt.Correction> pending;
    /** Per chat, the newest message of mine already learned from; kept in memory, so a restart learns once again. */
    private final Map<String, String> learnedUpTo = new ConcurrentHashMap<>();
    /** What was sent from drafts lately; in memory. */
    private final java.util.Set<String> aiTexts = new java.util.LinkedHashSet<>();

    WritingStyle(Path dir) {
        styleFile = dir.resolve("writing-style.md");
        correctionsFile = dir.resolve("writing-style-corrections.json");
        pending = new ArrayList<>(load(correctionsFile));
    }

    public static synchronized WritingStyle get() {
        if (instance == null) instance = new WritingStyle(Settings.get().dir());
        return instance;
    }

    /** Empty when nothing was learned yet. */
    public synchronized String text() {
        try {
            return Files.exists(styleFile) ? Files.readString(styleFile, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    public synchronized void save(String text) {
        write(styleFile, text == null ? "" : text);
    }

    /** What the person sent instead of {@code draft}; the same text, give or take spaces, teaches nothing. */
    public synchronized void addCorrection(String draft, String sent) {
        if (draft == null || sent == null || draft.strip().equals(sent.strip())) return;
        pending.add(new StylePrompt.Correction(draft.strip(), sent.strip()));
        while (pending.size() > MAX_PENDING) pending.remove(0);
        savePending();
    }

    /** Oldest first. A copy. */
    public synchronized List<StylePrompt.Correction> pending() {
        return List.copyOf(pending);
    }

    /** The oldest {@code n} were learned from. */
    public synchronized void used(int n) {
        pending.subList(0, Math.min(n, pending.size())).clear();
        savePending();
    }

    /** Everything learned and pending goes. */
    public synchronized void forget() {
        save("");
        pending.clear();
        learnedUpTo.clear();
        aiTexts.clear();
        savePending();
    }

    /** Sent from a draft: the AI's words, not the person's, so they're never learned from as their writing. */
    public synchronized void writtenByAi(String text) {
        if (text == null) return;
        aiTexts.add(text.strip());
        while (aiTexts.size() > MAX_AI_TEXTS) aiTexts.remove(aiTexts.iterator().next());
    }

    synchronized boolean isWrittenByAi(String text) {
        return text != null && aiTexts.contains(text.strip());
    }

    /**
     * Saves what was learned, uses up the corrections it came from and marks the chat as learned, but only if the
     * file is still {@code readAs}: the person may have changed it by hand meanwhile (Ajustes), and that wins.
     */
    synchronized boolean commit(String readAs, String learned, int correctionsUsed, String chatId, String newest) {
        if (!text().equals(readAs)) return false;
        save(learned);
        used(correctionsUsed);
        learnedUpTo(chatId, newest);
        return true;
    }

    String learnedUpTo(String chatId) {
        return learnedUpTo.get(chatId);
    }

    void learnedUpTo(String chatId, String messageId) {
        if (messageId != null) learnedUpTo.put(chatId, messageId);
    }

    private void savePending() {
        try {
            write(correctionsFile, JSON.writeValueAsString(pending));
        } catch (IOException e) {
            System.err.println("Could not save the draft corrections: " + e.getClass().getSimpleName());
        }
    }

    private static List<StylePrompt.Correction> load(Path f) {
        try {
            return Files.exists(f) ? JSON.readValue(f.toFile(), new TypeReference<List<StylePrompt.Correction>>() { }) : List.of();
        } catch (IOException e) {
            return List.of(); // damaged: start over, it's only what wasn't learned yet
        }
    }

    /** Through a .part file, so a crash never leaves half a file. */
    private static void write(Path f, String text) {
        try {
            Files.createDirectories(f.getParent());
            Path part = f.resolveSibling(f.getFileName() + ".part");
            Files.writeString(part, text, StandardCharsets.UTF_8);
            Files.move(part, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("Could not save " + f.getFileName() + ": " + e.getClass().getSimpleName());
        }
    }
}
