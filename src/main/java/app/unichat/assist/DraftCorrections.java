package app.unichat.assist;

import app.unichat.model.Message;
import app.unichat.model.MessageType;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The AI's last draft in each chat, until the person sends something there: then the two are a correction for
 * {@link WritingStyle} (or nothing, if they're the same, or if what was sent is another message altogether). A draft is
 * only matched in its own chat. Pure.
 */
public final class DraftCorrections {
    /**
     * Below this {@link #similarity}, what was sent is another message, not the draft corrected. Restyled drafts measured
     * 0.46 to 0.86 ("Claro, voy" → "Claro, ya voy" 0.86; a greeting cut to half, or "Lo siento mucho, no podré ir hoy." →
     * "uy no puedo ir hoy, sorry", 0.46), while a message about something else shared 0 to 0.24 ("ok", "jaja", another
     * topic): 0.4 sits in that gap, nearer the corrections' side. {@code DraftCorrectionsTest} has the examples.
     */
    static final double MIN_SIMILARITY = 0.4;
    /** The messages a correction answered: at most this many, each cut to {@link #CONTEXT_CHARS}. */
    static final int CONTEXT_MESSAGES = 3;
    static final int CONTEXT_CHARS = 200;
    /** Longer texts are compared by their start. */
    private static final int COMPARED_CHARS = 4000;

    private final Map<String, String> drafts = new ConcurrentHashMap<>();

    public void drafted(String chatId, String draft) {
        drafts.put(chatId, draft);
    }

    /** The person emptied the box: that draft wasn't sent, and what comes next isn't a correction of it. */
    public void forget(String chatId) {
        if (chatId != null) drafts.remove(chatId);
    }

    /**
     * The draft and what was sent instead, with {@code context} (what it answered), if there was a draft in that chat
     * and what was sent is that draft changed rather than another message; the draft is used up either way.
     */
    public StylePrompt.Correction sent(String chatId, String text, String context) {
        String draft = drafts.remove(chatId);
        if (draft == null || text == null || similarity(draft, text) < MIN_SIMILARITY) return null;
        return new StylePrompt.Correction(draft, text, context, chatId);
    }

    /** The newest messages from others before {@code chat}'s last ones of mine, oldest first, one line each; null with none. */
    public static String context(List<Message> chat) {
        List<String> out = new ArrayList<>();
        int i = chat.size() - 1;
        while (i >= 0 && chat.get(i).outgoing()) i--;
        for (; i >= 0 && out.size() < CONTEXT_MESSAGES && !chat.get(i).outgoing(); i--) {
            Message m = chat.get(i);
            if (m.type() != MessageType.TEXT || m.deleted() || m.body() == null || m.body().isBlank()) continue;
            String line = m.body().strip().replaceAll("\\s+", " ");
            out.add(0, line.length() > CONTEXT_CHARS ? line.substring(0, CONTEXT_CHARS) + "…" : line);
        }
        return out.isEmpty() ? null : String.join("\n", out);
    }

    /**
     * How much {@code a} and {@code b} share, from 0 (nothing) to 1 (the same): the Sørensen–Dice coefficient of their
     * pairs of letters side by side, ignoring case, accents and spacing. Unlike an edit distance, a draft cut short
     * (people write shorter than the AI) still shares most of what's left.
     */
    static double similarity(String a, String b) {
        String x = normal(a), y = normal(b);
        Map<String, Integer> px = pairs(x), py = pairs(y);
        int total = Math.max(x.length() - 1, 0) + Math.max(y.length() - 1, 0);
        if (total == 0) return x.equals(y) ? 1 : 0;
        int shared = 0;
        for (Map.Entry<String, Integer> e : px.entrySet()) shared += Math.min(e.getValue(), py.getOrDefault(e.getKey(), 0));
        return 2.0 * shared / total;
    }

    /** Each pair of letters side by side, and how many times it's there. */
    private static Map<String, Integer> pairs(String s) {
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i + 2 <= s.length(); i++) out.merge(s.substring(i, i + 2), 1, Integer::sum);
        return out;
    }

    private static String normal(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").strip();
        return n.length() > COMPARED_CHARS ? n.substring(0, COMPARED_CHARS) : n;
    }
}
