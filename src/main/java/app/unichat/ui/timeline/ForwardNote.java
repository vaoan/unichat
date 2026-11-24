package app.unichat.ui.timeline;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.settings.Settings;
import org.jspecify.annotations.Nullable;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a forward says about where it came from: who wrote it, the chat and network, and when, each the person's choice
 * ("↪ Forwarded from Ana · Familia (WhatsApp) · Nov 12 09:14"); none chosen, nothing (the default). Pure, tested.
 * It goes to other people, who may not read the person's language: English in every UI language.
 */
record ForwardNote(boolean who, boolean where, boolean when) {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ENGLISH);

    /** The choices Ajustes starts each forward with. */
    static ForwardNote saved() {
        Settings s = Settings.get();
        return new ForwardNote(s.forwardSaysWho(), s.forwardSaysWhere(), s.forwardSaysWhen());
    }

    boolean saysAnything() { return who || where || when; }

    /** The line for {@code m} of {@code from}, or null when nothing is chosen. */
    @Nullable String of(Message m, Chat from, ZoneId zone) {
        if (!saysAnything()) return null;
        List<String> parts = new ArrayList<>();
        parts.add(who ? "Forwarded from " + shown(m.senderName()) : "Forwarded");
        if (where && from != null) parts.add(shown(from.title()) + " (" + from.network().displayName() + ")");
        if (when) parts.add(STAMP.format(m.timestamp().atZone(zone)));
        return "↪ " + String.join(" · ", parts);
    }

    /** A name strangers chose, on one line and without marks that turn text around: it goes as one's own words. */
    static String shown(String name) {
        return app.unichat.support.Lines.one(name);
    }
}
