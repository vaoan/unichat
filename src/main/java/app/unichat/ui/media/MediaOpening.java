package app.unichat.ui.media;

import app.unichat.ui.Browser;
import app.unichat.model.Message;
import app.unichat.service.MediaSource;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.stage.Window;

import java.util.function.Consumer;

/** A message's media opened outside the chat, the same from the timeline, the info panel and the media panel. */
public final class MediaOpening {
    private MediaOpening() {}

    /** With the system's app, once downloaded; a failure is said in {@code toast}, never a click that does nothing. */
    static void file(MediaSource svc, Message m, Consumer<String> toast) {
        if (m.mediaUrl() == null) {
            toast.accept(I18n.t("media.downloadFailed"));
            return;
        }
        svc.media(m.mediaUrl(), 0, 0).whenComplete((p, err) -> {
            if (err != null) Platform.runLater(() -> toast.accept(I18n.t("media.downloadFailed")));
            else Browser.openFile(p, () -> toast.accept(I18n.t("media.openFailed")));
        });
    }

    /**
     * In the video player. The window's audio ({@code audio}, null where there's none) pauses first: a video from the
     * info panel played over the voice note. FX thread.
     */
    public static void video(Window owner, MediaSource svc, Message m, String title, AudioPlayback audio, Consumer<String> toast) {
        if (audio != null) audio.pause();
        VideoViewer.show(owner, svc.media(m.mediaUrl(), 0, 0), title, () -> MediaSaver.save(owner, svc, m, toast),
                p -> Browser.openFile(p, () -> toast.accept(I18n.t("video.openFailed"))));
    }
}
