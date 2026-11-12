package app.unichat.video;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.settings.Settings;
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

    /**
     * Whether {@code m} is a GIF that came as a video, silent, and as long as Ajustes allows ({@link #longestSeconds}:
     * none by default, as Telegram, which makes a GIF of any silent H.264 video up to 10 MB, whatever its length; one
     * whose length isn't said is taken as short). A longer one stays its video, whole.
     */
    public static boolean fits(Message m) {
        int longest = longestSeconds();
        return is(m) && !saysSound(m) && (longest == NO_LIMIT || m.durationMs() <= longest * 1000L);
    }

    /** {@link #longestSeconds} when any length goes, as Telegram has it. */
    public static final int NO_LIMIT = 0;

    /** The bridge says it has sound ({@code fi.mau.no_audio} false): a GIF would drop it. The file is asked too ({@link #toGif}). */
    private static boolean saysSound(Message m) {
        var info = m.mediaInfo();
        return info != null && info.has("fi.mau.no_audio") && !info.path("fi.mau.no_audio").asBoolean(true);
    }

    /** Thrown by {@link #toGif} for a video with sound: it stays a video. */
    public static final class HasSound extends IOException {
        HasSound() { super("it has sound"); }
    }

    /** {@link #fits} and Ajustes says to: saved as a GIF (a forward asks its own sheet, which starts from this). */
    public static boolean becomesGif(Message m) {
        return on() && fits(m);
    }

    private static final String KEY = "video.gifVideosAsGif";
    /** Ajustes → Redes' choices: the longest one made a GIF, the most a forwarded one may weigh, its widest. */
    public static final java.util.List<Integer> LONGEST_CHOICES = java.util.List.of(NO_LIMIT, 10, 15, 30),
            MB_CHOICES = java.util.List.of(8, 10, 25, 50), SIDE_CHOICES = java.util.List.of(320, 480, 512);
    private static final String LONGEST_KEY = "video.gifLongestSeconds", MB_KEY = "video.gifMaxMb", SIDE_KEY = "video.gifMaxSide";

    public static int longestSeconds() { return chosen(LONGEST_KEY, LONGEST_CHOICES, NO_LIMIT); }

    public static void setLongestSeconds(int s) { Settings.get().set(LONGEST_KEY, Integer.toString(s)); }

    /** 10 MB by default: Discord refuses files over that without Nitro. */
    public static int maxMb() { return chosen(MB_KEY, MB_CHOICES, 10); }

    public static void setMaxMb(int mb) { Settings.get().set(MB_KEY, Integer.toString(mb)); }

    public static int maxSide() { return chosen(SIDE_KEY, SIDE_CHOICES, 512); }

    public static void setMaxSide(int px) { Settings.get().set(SIDE_KEY, Integer.toString(px)); }

    /** The setting when it's one of {@code choices} (it travels with the person: any other value is a stranger's), else {@code def}. */
    private static int chosen(String key, java.util.List<Integer> choices, int def) {
        int v = Settings.get().getInt(key, def, 0);
        return choices.contains(v) ? v : def;
    }

    /** Ajustes → Redes: off, such a GIF is saved and forwarded as the video it came as, always. */
    public static boolean on() { return Settings.get().getBool(KEY, true); }

    public static void setOn(boolean on) { Settings.get().setBool(KEY, on); }

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
        if (hasSound(ffmpeg, video)) throw new HasSound();
        if (PartFile.firstOf(out, WebmStickers.TIMEOUT_SECONDS,
                List.of(into -> WebmStickers.command(ffmpeg, video, "mov", into, "gif", false, maxSide(), null))) == null) {
            throw new IOException(I18n.t("video.gifFailed"));
        }
        return out;
    }

    /** {@code video} as a GIF ({@link #toGif}), or {@code video} itself, which still plays, when it has sound or can't be made (logged). */
    public static Path gifOr(Path video) {
        try {
            return toGif(video, p -> { });
        } catch (HasSound e) {
            return video;
        } catch (IOException e) {
            System.err.println("GIF from its video failed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + "); keeping the video");
            return video;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return video;
        }
    }

    /** Whether {@code video} has an audio stream, as ffmpeg reads it (only the file itself, as an MP4). */
    static boolean hasSound(Path ffmpeg, Path video) throws IOException, InterruptedException {
        app.unichat.support.Processes.Result r = app.unichat.support.Processes.capture(new ProcessBuilder(ffmpeg.toString(), "-nostdin",
                "-hide_banner", "-protocol_whitelist", "file", "-f", "mov", "-i", video.toString()).redirectErrorStream(true),
                WebmStickers.TIMEOUT_SECONDS);
        return r.out().contains(" Audio: ");
    }
}
