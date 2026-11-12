package app.unichat.ui.timeline;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * What a forward says about where it came from: who wrote it, the chat and network, and when, each the person's choice
 * ("↪ Reenviado de Ana · Familia (WhatsApp) · 12 nov 09:14"); none chosen, nothing (the default). Pure, tested.
 */
record ForwardNote(boolean who, boolean where, boolean when) {
    /** The choices Ajustes starts each forward with. */
    static ForwardNote saved() {
        Settings s = Settings.get();
        return new ForwardNote(s.forwardSaysWho(), s.forwardSaysWhere(), s.forwardSaysWhen());
    }

    boolean saysAnything() { return who || where || when; }

    /** The line for {@code m} of {@code from}, or null when nothing is chosen. */
    String of(Message m, Chat from, ZoneId zone) {
        if (!saysAnything()) return null;
        List<String> parts = new ArrayList<>();
        parts.add(who ? I18n.t("forward.note.from", shown(m.senderName())) : I18n.t("forward.note.plain"));
        if (where && from != null) parts.add(I18n.t("forward.note.where", shown(from.title()), from.network().displayName()));
        if (when) parts.add(DateTimeFormatter.ofPattern(I18n.t("forward.note.stamp"), I18n.locale()).format(m.timestamp().atZone(zone)));
        return "↪ " + String.join(" · ", parts);
    }

    /** A name strangers chose, on one line and without marks that turn text around: it goes as one's own words. */
    static String shown(String name) {
        return name.replaceAll("[\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069]+", " ").strip();
    }
}
