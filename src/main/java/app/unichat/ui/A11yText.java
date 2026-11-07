package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.model.Network;
import app.unichat.support.I18n;

import java.time.Instant;
import java.time.ZoneId;

/** What a screen reader or the voice says for messages and chats. Text only: whatever it depends on arrives as a parameter. */
final class A11yText {
    private A11yText() { }

    /** A message row. {@code replied} is the message it answers if loaded, {@code transcript} its voice note's text if done. */
    static String message(Message m, Message replied, String transcript, ZoneId zone) {
        StringBuilder sb = new StringBuilder(m.outgoing() ? I18n.t("common.you") : m.senderName());
        sb.append(", ").append(Formats.time(m.timestamp(), zone)).append(": ");
        if (m.type() == MessageType.SYSTEM) return m.body();
        if (replied != null) sb.append(I18n.t("a11y.replyingTo", replied.outgoing() ? I18n.t("a11y.toYou") : replied.senderName())).append(". ");
        sb.append(switch (m.type()) {
            case IMAGE -> I18n.t("media.image") + (m.fileName() == null ? "" : " " + m.fileName());
            case STICKER -> I18n.t("media.sticker");
            default -> m.preview();
        });
        if (m.htmlText() != null && !m.body().isBlank()) sb.append(". ").append(m.htmlText());
        if (m.edited() && !m.deleted()) sb.append(", ").append(I18n.t("message.edited"));
        if (transcript != null) sb.append(". ").append(I18n.t("a11y.transcript", transcript.isEmpty() ? I18n.t("a11y.noSpeech") : transcript));
        if (!m.reactions().isEmpty()) {
            StringBuilder list = new StringBuilder();
            m.reactions().forEach((emoji, users) -> list.append(m.reactionLabel(emoji)).append(' ').append(users.size()).append(", "));
            list.setLength(list.length() - 2);
            sb.append(". ").append(I18n.t("a11y.reactions", list));
        }
        if (m.outgoing()) sb.append(switch (m.status()) {
            case SENDING -> ". " + I18n.t("a11y.status.sending");
            case SENT -> ". " + I18n.t("a11y.status.sent");
            case DELIVERED -> ". " + I18n.t("a11y.status.delivered");
            case READ -> ". " + I18n.t("a11y.status.read");
            case FAILED -> ". " + I18n.t("a11y.status.failed");
        });
        return sb.toString();
    }

    /** A chat; {@code account} names the login when the network has several. */
    static String chat(Chat c, String account, Instant now, ZoneId zone) {
        StringBuilder sb = new StringBuilder(c.title()).append(", ").append(c.network().displayName());
        if (account != null) sb.append(" (").append(account).append(')');
        if (c.group()) sb.append(", ").append(I18n.t("a11y.group"));
        if (c.mentionCount() > 0) sb.append(", ").append(I18n.t("a11y.mentioned"));
        if (c.unreadCount() > 0) sb.append(", ").append(I18n.n("a11y.unread", c.unreadCount()));
        else if (c.markedUnread()) sb.append(", ").append(I18n.t("a11y.markedUnread"));
        if (c.pinned()) sb.append(", ").append(I18n.t("a11y.pinned"));
        if (c.muted()) sb.append(", ").append(I18n.t("a11y.muted"));
        if (c.reminderAt() != null) sb.append(", ").append(I18n.t("a11y.reminder"));
        if (!c.typing().isEmpty()) sb.append(". ").append(Formats.typing(c.typing()));
        else if (!c.lastPreview().isEmpty()) sb.append(". ").append(c.lastOutgoing() ? I18n.t("common.you") + ": " : c.group() && c.lastSenderName() != null
                ? c.lastSenderName() + ": " : "").append(c.lastPreview());
        String stamp = Formats.listStamp(c.lastActivity(), now, zone);
        if (!stamp.isEmpty()) sb.append(". ").append(stamp);
        return sb.toString();
    }

    /** A server you're in that isn't in Unichat yet; {@code busy} while it's being added. */
    static String offer(Chat c, boolean busy) {
        return I18n.t("a11y.offer", c.title(), c.network().displayName(), I18n.t(busy ? "a11y.adding" : "a11y.add"));
    }

    /** A server's row: {@code unread} sums its channels, {@code latest} is the channel that spoke last, if any. */
    static String server(Chat c, int unread, Chat latest) {
        return I18n.t(c.network() == Network.DISCORD ? "a11y.serverDiscord" : "a11y.serverGroup", c.title(), c.network().displayName())
                + (unread > 0 ? ", " + I18n.t("a11y.unreadCount", unread) : "") + (c.muted() ? ", " + I18n.t("a11y.muted") : "")
                + (latest == null ? "" : ". " + latest.title() + ": " + latest.lastPreview()) + ". " + I18n.t("a11y.opensChannels");
    }

    static String unjoined(Chat c) {
        return I18n.t("a11y.unjoined", c.title());
    }

    /** "Mensaje de Laura en Familia, WhatsApp: hola" (only the sender when previews are hidden). */
    static String spoken(Chat chat, Message m, boolean showPreview) {
        String who = chat.group() ? I18n.t("a11y.senderIn", m.senderName(), chat.title()) : chat.title();
        String head = I18n.t("a11y.messageFrom", who, chat.network().displayName());
        return showPreview ? head + ": " + m.preview() : head;
    }
}
