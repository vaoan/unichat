package app.unichat.ui.media;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.service.ChatReader;
import app.unichat.service.MediaSource;
import javafx.stage.Window;

import java.util.List;
import java.util.function.Consumer;

/**
 * A chat's pictures in order, for the photo viewer to step through with ← and →: photos, and pictures sent as
 * documents (the original, uncompressed), as Telegram's viewer does. Stickers and reels stay on their own.
 */
final class Gallery {
    private Gallery() {
    }

    static boolean isPicture(Message m) {
        if (m.mediaUrl() == null || m.deleted()) return false;
        if (m.type() == MessageType.IMAGE) return !m.isReelLink();
        return m.type() == MessageType.FILE && FilePreview.isPicture(m);
    }

    /** The pictures of {@code timeline}, oldest first. */
    static List<Message> pictures(List<Message> timeline) {
        return timeline.stream().filter(Gallery::isPicture).toList();
    }

    /** Where {@code m} is among {@code pictures}, by id; -1 if it isn't. */
    static int indexOf(List<Message> pictures, Message m) {
        for (int i = 0; i < pictures.size(); i++) if (pictures.get(i).id().equals(m.id())) return i;
        return -1;
    }

    /**
     * Opens {@code m} in the viewer, with the rest of its chat's pictures before and after it when {@code svc} can
     * list them; just {@code m} otherwise.
     */
    static void open(Window owner, MediaSource svc, Message m, Consumer<String> toast) {
        List<Message> all = svc instanceof ChatReader r && m.chatId() != null ? pictures(r.messages(m.chatId())) : List.of();
        int at = indexOf(all, m);
        if (at < 0) {
            all = List.of(m);
            at = 0;
        }
        List<MediaViewer.Item> items = all.stream().map(p -> new MediaViewer.Item(
                p.fileName() == null ? app.unichat.support.I18n.t("media.image") : p.fileName(),
                () -> svc.media(p.mediaUrl(), 0, 0),
                () -> MediaSaver.save(owner, svc, p, toast))).toList();
        MediaViewer.show(owner, items, at);
    }
}
