package app.unichat.assist;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The AI's last draft in each chat, until the person sends something there: then the two are a correction for
 * {@link WritingStyle} (or nothing, if they're the same). A draft is only matched in its own chat. Pure.
 */
public final class DraftCorrections {
    private final Map<String, String> drafts = new ConcurrentHashMap<>();

    public void drafted(String chatId, String draft) {
        drafts.put(chatId, draft);
    }

    /** The person emptied the box: that draft wasn't sent, and what comes next isn't a correction of it. */
    public void forget(String chatId) {
        if (chatId != null) drafts.remove(chatId);
    }

    /** The draft and what was sent instead, if there was a draft in that chat; it's used up either way. */
    public StylePrompt.Correction sent(String chatId, String text) {
        String draft = drafts.remove(chatId);
        return draft == null ? null : new StylePrompt.Correction(draft, text);
    }
}
