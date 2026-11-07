package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.model.Network;

import java.time.Instant;
import java.time.ZoneId;

/** What a screen reader or the voice says for messages and chats. Text only: whatever it depends on arrives as a parameter. */
final class A11yText {
    private A11yText() { }

    /** A message row. {@code replied} is the message it answers if loaded, {@code transcript} its voice note's text if done. */
    static String message(Message m, Message replied, String transcript, ZoneId zone) {
        StringBuilder sb = new StringBuilder(m.outgoing() ? "Tú" : m.senderName());
        sb.append(", ").append(Formats.time(m.timestamp(), zone)).append(": ");
        if (m.type() == MessageType.SYSTEM) return m.body();
        if (replied != null) sb.append("respondiendo a ").append(replied.outgoing() ? "ti" : replied.senderName()).append(". ");
        sb.append(switch (m.type()) {
            case IMAGE -> "Imagen" + (m.fileName() == null ? "" : " " + m.fileName());
            case STICKER -> "Sticker";
            default -> m.preview();
        });
        if (m.htmlText() != null && !m.body().isBlank()) sb.append(". ").append(m.htmlText());
        if (m.edited() && !m.deleted()) sb.append(", editado");
        if (transcript != null) sb.append(". Transcripción: ").append(transcript.isEmpty() ? "sin voz" : transcript);
        if (!m.reactions().isEmpty()) {
            sb.append(". Reacciones: ");
            m.reactions().forEach((emoji, users) -> sb.append(m.reactionLabel(emoji)).append(' ').append(users.size()).append(", "));
            sb.setLength(sb.length() - 2);
        }
        if (m.outgoing()) sb.append(switch (m.status()) {
            case SENDING -> ". Enviando";
            case SENT -> ". Enviado";
            case DELIVERED -> ". Entregado";
            case READ -> ". Leído";
            case FAILED -> ". No se pudo enviar";
        });
        return sb.toString();
    }

    /** A chat; {@code account} names the login when the network has several. */
    static String chat(Chat c, String account, Instant now, ZoneId zone) {
        StringBuilder sb = new StringBuilder(c.title()).append(", ").append(c.network().displayName());
        if (account != null) sb.append(" (").append(account).append(')');
        if (c.group()) sb.append(", grupo");
        if (c.mentionCount() > 0) sb.append(", te mencionaron");
        if (c.unreadCount() > 0) sb.append(", ").append(c.unreadCount()).append(c.unreadCount() == 1 ? " mensaje sin leer" : " mensajes sin leer");
        else if (c.markedUnread()) sb.append(", marcado como no leído");
        if (c.pinned()) sb.append(", fijado");
        if (c.muted()) sb.append(", silenciado");
        if (c.reminderAt() != null) sb.append(", con recordatorio");
        if (!c.typing().isEmpty()) sb.append(". ").append(Formats.typing(c.typing()));
        else if (!c.lastPreview().isEmpty()) sb.append(". ").append(c.lastOutgoing() ? "Tú: " : c.group() && c.lastSenderName() != null
                ? c.lastSenderName() + ": " : "").append(c.lastPreview());
        String stamp = Formats.listStamp(c.lastActivity(), now, zone);
        if (!stamp.isEmpty()) sb.append(". ").append(stamp);
        return sb.toString();
    }

    /** A server you're in that isn't in Unichat yet; {@code busy} while it's being added. */
    static String offer(Chat c, boolean busy) {
        return c.title() + ", servidor de " + c.network().displayName() + " sin agregar, " + (busy ? "Agregando" : "Agregar");
    }

    /** A server's row: {@code unread} sums its channels, {@code latest} is the channel that spoke last, if any. */
    static String server(Chat c, int unread, Chat latest) {
        return c.title() + ", " + (c.network() == Network.DISCORD ? "servidor" : "grupo") + " de "
                + c.network().displayName() + (unread > 0 ? ", " + unread + " sin leer" : "") + (c.muted() ? ", silenciado" : "")
                + (latest == null ? "" : ". " + latest.title() + ": " + latest.lastPreview()) + ". Abre sus canales";
    }

    static String unjoined(Chat c) {
        return c.title() + ", canal sin unirte, Unirse";
    }

    /** "Mensaje de Laura en Familia, WhatsApp: hola" (only the sender when previews are hidden). */
    static String spoken(Chat chat, Message m, boolean showPreview) {
        String who = chat.group() ? m.senderName() + " en " + chat.title() : chat.title();
        String head = "Mensaje de " + who + ", " + chat.network().displayName();
        return showPreview ? head + ": " + m.preview() : head;
    }
}
