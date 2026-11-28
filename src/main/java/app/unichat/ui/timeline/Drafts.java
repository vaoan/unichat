package app.unichat.ui.timeline;

import app.unichat.service.ChatService;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * What the person wrote and didn't send, per chat, for as long as the session: the window may be built again (closed to
 * the menu bar a while, the language changed) and its message box with it, and a half-written text was lost with the
 * old one. Held weakly by service: a session ended takes its drafts with it. Used on the FX thread.
 */
final class Drafts {
    private static final Map<ChatService, Map<String, String>> BY_SERVICE = new WeakHashMap<>();

    private Drafts() {}

    /** {@code svc}'s drafts, by chat id. */
    static synchronized Map<String, String> of(ChatService svc) {
        return BY_SERVICE.computeIfAbsent(svc, s -> new HashMap<>());
    }
}
