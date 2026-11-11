package app.unichat.assist;

import app.unichat.settings.Settings;
import app.unichat.support.SafeFile;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What's known of how the person writes ({@code writing-style.md}, which travels with them) and the corrections to
 * drafts not learned from yet ({@code writing-style-corrections.json}, this computer's; the last {@link #MAX_PENDING}),
 * with the versions learning replaced ({@link StyleHistory}, this computer's too).
 */
public final class WritingStyle {
    static final int MAX_PENDING = 20;
    /** Corrections once learned from, kept as examples for drafts ({@code writing-style-learned.json}, this computer's). */
    static final int MAX_LEARNED = 40;
    private static final int MAX_AI_TEXTS = 200;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static WritingStyle instance;

    private final Path styleFile;
    private final Path correctionsFile;
    private final List<StylePrompt.Correction> pending;
    private final Path learnedFile;
    private final List<StylePrompt.Correction> learned;
    private final StyleHistory history;
    /** Per chat, the newest message of mine already learned from; kept in memory, so a restart learns once again. */
    private final Map<String, String> learnedUpTo = new ConcurrentHashMap<>();
    /** What was sent from drafts lately; in memory. */
    private final java.util.Set<String> aiTexts = new java.util.LinkedHashSet<>();

    /** What learning changed last: {@code when}, and the traits it added and removed since (hand edits count too). */
    public record LastLearned(Instant when, StyleDiff changes) {}

    WritingStyle(Path dir) { this(dir, Clock.systemUTC()); }

    WritingStyle(Path dir, Clock clock) {
        history = new StyleHistory(dir.resolve(StyleHistory.FOLDER), clock);
        styleFile = dir.resolve("writing-style.md");
        correctionsFile = dir.resolve("writing-style-corrections.json");
        pending = new ArrayList<>(load(correctionsFile));
        learnedFile = dir.resolve("writing-style-learned.json");
        learned = new ArrayList<>(load(learnedFile));
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
    public void addCorrection(String draft, String sent) { addCorrection(new StylePrompt.Correction(draft, sent)); }

    /** As {@link #addCorrection(String, String)}, with what the draft answered. */
    public synchronized void addCorrection(StylePrompt.Correction c) {
        String draft = c.draft(), sent = c.sent();
        if (draft == null || sent == null || draft.strip().equals(sent.strip())) return;
        pending.add(new StylePrompt.Correction(draft.strip(), sent.strip(), c.context(), c.chatId()));
        while (pending.size() > MAX_PENDING) pending.remove(0);
        savePending();
    }

    /** Oldest first. A copy. */
    public synchronized List<StylePrompt.Correction> pending() {
        return List.copyOf(pending);
    }

    /** The oldest {@code n} were learned from: kept as examples for drafts, no longer pending. */
    public synchronized void used(int n) {
        List<StylePrompt.Correction> done = pending.subList(0, Math.min(n, pending.size()));
        learned.addAll(done);
        while (learned.size() > MAX_LEARNED) learned.remove(0);
        done.clear();
        savePending();
        saveLearned();
    }

    /**
     * Up to {@code max} corrections for a draft in {@code chatId}: that chat's first, then the rest, newest first each,
     * learned from or still pending. Learned ones were dropped before, and drafts only ever saw the style's summary.
     */
    public synchronized List<StylePrompt.Correction> examples(String chatId, int max) {
        List<StylePrompt.Correction> all = new ArrayList<>(learned);
        all.addAll(pending);
        java.util.Collections.reverse(all);
        List<StylePrompt.Correction> out = new ArrayList<>();
        for (StylePrompt.Correction c : all) if (out.size() < max && chatId != null && chatId.equals(c.chatId())) out.add(c);
        for (StylePrompt.Correction c : all) if (out.size() < max && !out.contains(c)) out.add(c);
        return out;
    }

    /** Everything learned and pending goes. */
    public synchronized void forget() {
        save("");
        history.clear();
        pending.clear();
        learned.clear();
        saveLearned();
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
        history.replaced(readAs);
        save(learned);
        used(correctionsUsed);
        learnedUpTo(chatId, newest);
        return true;
    }

    /** The last change learning made, against the file as it is now; null when there's none kept. */
    public synchronized LastLearned lastLearned() {
        StyleHistory.Version v = history.lastLearned();
        return v == null ? null : new LastLearned(v.when(), StyleDiff.between(v.text(), text()));
    }

    /**
     * Back to the version before the last learning; the one undone stays in the history (as undone), so nothing is
     * lost. False when there's nothing to undo.
     */
    public synchronized boolean undoLast() {
        StyleHistory.Version v = history.lastLearned();
        if (v == null) return false;
        String previous = v.text();
        history.undone(text());
        save(previous);
        history.remove(v);
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

    private void saveLearned() {
        try {
            write(learnedFile, JSON.writeValueAsString(learned));
        } catch (IOException e) {
            System.err.println("Could not save the learned corrections: " + e.getClass().getSimpleName());
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
            SafeFile.at(f, ".part").write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("Could not save " + f.getFileName() + ": " + e.getClass().getSimpleName());
        }
    }
}
