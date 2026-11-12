package app.unichat.video;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.settings.Settings;
import app.unichat.support.ConvertedFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * GIFs that came as a video: Telegram (and WhatsApp) keep every GIF as a silent MP4, and their bridges send it so,
 * marked as a GIF. Forwarded where an MP4 shows as a video to press play on (Discord), it goes as an animated WebP
 * made from the MP4 (playing by itself, every colour, a fraction of a GIF's weight); saved, it stays the MP4.
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

    /** The bridge says it has sound ({@code fi.mau.no_audio} false): a picture would drop it. The file is asked too ({@link #toWebp}). */
    private static boolean saysSound(Message m) {
        var info = m.mediaInfo();
        return info != null && info.has("fi.mau.no_audio") && !info.path("fi.mau.no_audio").asBoolean(true);
    }

    /** Thrown by {@link #toWebp} for a video with sound: it stays a video. */
    public static final class HasSound extends IOException {
        HasSound() { super("it has sound"); }
    }

    private static final String KEY = "video.gifVideosAsGif";
    /** Ajustes → Redes' choices: the longest one made a picture, the most a forwarded one may weigh, its longest side. */
    public static final java.util.List<Integer> LONGEST_CHOICES = java.util.List.of(NO_LIMIT, 10, 15, 30),
            MB_CHOICES = java.util.List.of(8, 10, 25, 50), SIDE_CHOICES = java.util.List.of(320, 480, 640);
    private static final String LONGEST_KEY = "video.gifLongestSeconds", MB_KEY = "video.gifMaxMb", SIDE_KEY = "video.gifMaxSide";

    public static int longestSeconds() { return chosen(LONGEST_KEY, LONGEST_CHOICES, NO_LIMIT); }

    public static void setLongestSeconds(int s) { Settings.get().set(LONGEST_KEY, Integer.toString(s)); }

    /** 10 MB by default: Discord refuses files over that without Nitro. */
    public static int maxMb() { return chosen(MB_KEY, MB_CHOICES, 10); }

    public static void setMaxMb(int mb) { Settings.get().set(MB_KEY, Integer.toString(mb)); }

    /** Its longest side (a video standing up is limited by its height): 480 px by default, as GIF sites make them. */
    public static int maxSide() { return chosen(SIDE_KEY, SIDE_CHOICES, 480); }

    /** The most a forwarded one may weigh ({@link #maxMb}). */
    public static long maxBytes() { return (long) maxMb() << 20; }

    public static void setMaxSide(int px) { Settings.get().set(SIDE_KEY, Integer.toString(px)); }

    /** The setting when it's one of {@code choices} (it travels with the person: any other value is a stranger's), else {@code def}. */
    private static int chosen(String key, java.util.List<Integer> choices, int def) {
        int v = Settings.get().getInt(key, def, 0);
        return choices.contains(v) ? v : def;
    }

    /** Ajustes → Redes: off, such a GIF is forwarded as the video it came as (the forward sheet starts from this). */
    public static boolean on() { return Settings.get().getBool(KEY, true); }

    public static void setOn(boolean on) { Settings.get().setBool(KEY, on); }

    /** {@code name} with ".webp" in place of its extension. */
    public static String webpName(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name) + ".webp";
    }

    /**
     * How it's made: frames a second, longest side and WebP quality. As a GIF at the video's own rate a 3 s, 430 KB MP4
     * weighed 22.7 MB and a 26 s one 110 MB; an animated WebP keeps every colour at a fraction of a GIF's weight, and
     * Discord plays it by itself as it does a GIF.
     */
    record Quality(int fps, int side, int quality) {}

    /** The first is how it's made; the rest, smaller, are tried in turn when it must weigh less ({@link #webpUnder}). */
    static List<Quality> qualities() {
        int side = maxSide();
        java.util.LinkedHashSet<Quality> q = new java.util.LinkedHashSet<>(List.of(new Quality(15, side, 70)));
        q.add(new Quality(12, Math.min(side, 360), 60));
        q.add(new Quality(10, Math.min(side, 320), 50));
        return List.copyOf(q);
    }

    /**
     * {@code video} as an animated WebP of at most {@code maxBytes}, trying each smaller way in turn; {@code video} itself
     * when none fits, it has sound or it can't be made (no ffmpeg, no img2webp). Blocking.
     */
    public static Path webpUnder(Path video, long maxBytes) {
        for (Quality q : qualities()) {
            try {
                Path webp = toWebp(video, q, p -> { });
                if (Files.size(webp) <= maxBytes) return webp;
            } catch (HasSound e) {
                return video;
            } catch (IOException e) {
                System.err.println("Animated WebP from its video failed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + "); sending the video");
                return video;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return video;
            }
        }
        return video;
    }

    /**
     * {@code video} as an animated WebP made {@code q}, beside it in its cache folder (made once, {@link AnimatedWebp}).
     * Blocking; {@link WebmStickers.NoConverter} without ffmpeg, {@link HasSound} for a video with sound, an IOException
     * when it can't be made (no img2webp, or it failed).
     */
    static Path toWebp(Path video, Quality q, DoubleConsumer installProgress) throws IOException, InterruptedException {
        Path out = ConvertedFiles.beside(video, q.fps() + "fps-" + q.side() + ".webp");
        if (ConvertedFiles.reuse(out)) return out;
        Path ffmpeg = Ffmpeg.whenNeeded(installProgress);
        if (ffmpeg == null) throw new WebmStickers.NoConverter();
        Path img2webp = AnimatedWebp.img2webp();
        if (img2webp == null) throw new IOException("img2webp isn't there");
        AnimatedWebp.Probe probe = AnimatedWebp.probe(ffmpeg, video, AnimatedWebp.Source.MP4);
        if (probe.sound()) throw new HasSound();
        AnimatedWebp.make(ffmpeg, img2webp, video, AnimatedWebp.Source.MP4, out, q.fps(), q.side(), q.quality(), probe.seconds());
        return out;
    }
}
