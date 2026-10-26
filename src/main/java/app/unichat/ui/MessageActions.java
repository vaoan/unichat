package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.model.RoomFeatures;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/** Which actions a message's menu offers, following what the chat's network supports. Pure, tested. */
final class MessageActions {
    private MessageActions() {}

    enum Action { REPLY, COPY, OPEN_FILE, SAVE_AS, EDIT, DELETE_FOR_EVERYONE, DELETE_FOR_ME, FAVORITE }

    static Set<Action> of(Message m, RoomFeatures f, Instant now) {
        Set<Action> out = EnumSet.noneOf(Action.class);
        if (m.deleted()) return out;
        if (f.replies() || f.isUnknown()) out.add(Action.REPLY);
        if (!m.isMedia()) out.add(Action.COPY);
        if (m.mediaUrl() != null) {
            out.add(Action.OPEN_FILE);
            out.add(Action.SAVE_AS);
        }
        if (isStickerOrGif(m)) out.add(Action.FAVORITE);
        if (m.outgoing()) {
            if (!m.isMedia() && f.canEdit(m.timestamp(), now)) out.add(Action.EDIT);
            if (f.canDeleteForEveryone(m.timestamp(), now)) out.add(Action.DELETE_FOR_EVERYONE);
            else if (f.deleteForMe()) out.add(Action.DELETE_FOR_ME);
        }
        return out;
    }

    static boolean isStickerOrGif(Message m) {
        return m.type() == MessageType.STICKER || "image/gif".equals(m.mimeType());
    }
}
