package app.unichat.ui.timeline;

import app.unichat.autosave.AutoSave;
import app.unichat.model.Message;
import app.unichat.model.MessageStatus;
import app.unichat.model.MessageType;
import app.unichat.model.RoomFeatures;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/** Which actions a message's menu offers, following what the chat's network supports. Pure, tested. */
public final class MessageActions {
    private MessageActions() {}

    enum Action { REPLY, COPY, OPEN_FILE, SAVE_AS, SAVE_LINK, SAVED_TEXT, TRANSCRIBE, EDIT, FIX, DELETE_FOR_EVERYONE, DELETE_FOR_ME, FAVORITE, FORWARD, PIN }

    static Set<Action> of(Message m, RoomFeatures f, Instant now) {
        return of(m, f, now, false);
    }

    /** {@code myTextsKept}: the chat keeps what I send (auto-save's "Textos"), so mine have versions to see. */
    static Set<Action> of(Message m, RoomFeatures f, Instant now, boolean myTextsKept) {
        Set<Action> out = EnumSet.noneOf(Action.class);
        // What it said, from the texts' copy (or that there's none): all a deleted message has left, and an edited one's past.
        if (m.deleted()) return EnumSet.of(Action.SAVED_TEXT);
        // An edited one's past; mine where kept: what I wrote before the AI corrected it.
        if (m.edited() || myTextsKept && m.outgoing() && !m.isMedia()) out.add(Action.SAVED_TEXT);
        if (f.replies() || f.isUnknown()) out.add(Action.REPLY);
        if (!m.isMedia()) out.add(Action.COPY);
        if (m.mediaUrl() != null) {
            out.add(Action.OPEN_FILE);
            out.add(Action.SAVE_AS);
            if (m.type() == MessageType.AUDIO) out.add(Action.TRANSCRIBE);
        }
        // What a link leads to, saved into the chat's folder as auto-save would: the links auto-save takes. Not while mine
        // has only its temporary id (sending, failed): the name would carry that one, and auto-save save it again.
        boolean onServer = m.status() != MessageStatus.SENDING && m.status() != MessageStatus.FAILED;
        if (onServer && !AutoSave.links(m).isEmpty()) out.add(Action.SAVE_LINK);
        // A copy goes, so only what the server has (a file at a media address: the sender chose any other); a join or a
        // new name isn't a message to pass on.
        if (onServer && m.type() != MessageType.SYSTEM && (!m.isMedia() || app.unichat.model.Mxc.parse(m.mediaUrl()) != null)) {
            out.add(Action.FORWARD);
        }
        // Pinned in Unichat (no bridge carries pins): what the server has, so the id stays; never a join or a new name.
        if (onServer && m.type() != MessageType.SYSTEM) out.add(Action.PIN);
        if (isStickerOrGif(m)) out.add(Action.FAVORITE);
        if (m.outgoing()) {
            if (!m.isMedia() && f.canEdit(m.timestamp(), now)) {
                out.add(Action.EDIT);
                // Corrected and edited by itself, the message box left alone; once the server has it (its id is final).
                if (onServer) out.add(Action.FIX);
            }
            if (f.canDeleteForEveryone(m.timestamp(), now)) out.add(Action.DELETE_FOR_EVERYONE);
            else if (f.deleteForMe()) out.add(Action.DELETE_FOR_ME);
        }
        return out;
    }

    public static boolean isStickerOrGif(Message m) {
        // A GIF however its bridge sent it: a picture, or an MP4 or WebP marked as one (Telegram, WhatsApp, Discord's gifv).
        return m.type() == MessageType.STICKER || m.isGif();
    }
}
