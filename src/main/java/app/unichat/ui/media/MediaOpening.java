package app.unichat.ui.media;

import app.unichat.ui.Browser;
import app.unichat.model.Message;
import app.unichat.service.MediaSource;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
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

    /**
     * The video of a reel the bridge gave only as its picture and link, in the player: fetched by the engine that saves
     * links (`autosave/LinkVideo`, once, into the media cache) while the player waits. It opened Instagram before; it
     * still does when the video can't be had (a private reel, no cookies), and the player closes. FX thread.
     */
    public static void reel(Window owner, String url, AudioPlayback audio, Consumer<String> openLink) {
        if (audio != null) audio.pause();
        CompletableFuture<Path> video = app.unichat.autosave.LinkVideo.of(url);
        Stage player = VideoViewer.show(owner, video, I18n.t("media.reelTitle"), null,
                p -> Browser.openFile(p, () -> openLink.accept(url)));
        video.whenComplete((p, failed) -> {
            if (failed == null) return;
            System.err.println("Reel not playable here, opening its page: " + failed);
            Platform.runLater(() -> {
                player.close();
                openLink.accept(url);
            });
        });
    }
}
