package app.unichat.video;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.support.ConvertedFiles;
import app.unichat.support.I18n;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * GIFs that came as a video: Telegram (and WhatsApp) keep every GIF as a silent MP4, and their bridges send it so,
 * marked as a GIF. Saved, or forwarded where an MP4 shows as a video to press play on (Discord), it goes as the real
 * GIF it was sent as.
 */
public final class GifVideos {
    private GifVideos() {}

    /** A GIF that came as a video (not a sticker, which goes its own way; not a real GIF, which needs nothing). */
    public static boolean is(Message m) {
        return m.isGif() && m.type() == MessageType.VIDEO;
    }

    /** {@code name} with ".gif" in place of its extension. */
    public static String gifName(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name) + ".gif";
    }

    /**
     * {@code video} as an animated GIF, beside it in its cache folder (made once). Blocking; {@link WebmStickers.NoConverter}
     * without ffmpeg, an IOException when it can't be made.
     */
    public static Path toGif(Path video, DoubleConsumer installProgress) throws IOException, InterruptedException {
        Path out = ConvertedFiles.beside(video, "gif");
        if (ConvertedFiles.reuse(out)) return out;
        Path ffmpeg = Ffmpeg.whenNeeded(installProgress);
        if (ffmpeg == null) throw new WebmStickers.NoConverter();
        if (PartFile.firstOf(out, WebmStickers.TIMEOUT_SECONDS,
                List.of(into -> WebmStickers.command(ffmpeg, video, "mov", into, "gif", false))) == null) {
            throw new IOException(I18n.t("video.gifFailed"));
        }
        return out;
    }

    /** {@code video} as a GIF ({@link #toGif}), or {@code video} itself, which still plays, when it can't be made (logged). */
    public static Path gifOr(Path video) {
        try {
            return toGif(video, p -> { });
        } catch (IOException e) {
            System.err.println("GIF from its video failed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + "); keeping the video");
            return video;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return video;
        }
    }
}
