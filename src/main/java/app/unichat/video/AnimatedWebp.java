package app.unichat.video;

import app.unichat.support.BundledTools;
import app.unichat.support.I18n;
import app.unichat.support.Processes;
import app.unichat.support.SafeFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An animated WebP made from a video's frames: ffmpeg takes them out (as PAM, which img2webp reads without a PNG
 * library), libwebp's img2webp puts them together. Every colour and a smooth transparency at a fraction of a GIF's
 * weight, and Discord and WhatsApp play it by themselves. For Telegram's GIFs (silent MP4s) and video stickers (WebM).
 */
public final class AnimatedWebp {
    private AnimatedWebp() {}

    /**
     * What a video is read as (only the file itself: it's a stranger's): ffmpeg's demuxer, whether libvpx decodes it
     * (ffmpeg's own VP9 decoder drops the transparency) and whether its transparency is kept.
     */
    public record Source(String format, boolean libvpx, boolean alpha) {
        public static final Source MP4 = new Source("mov", false, false);
        public static final Source WEBM = new Source("matroska", true, true);
    }

    /** The most frames one may have (a long one gets fewer a second instead): each is a whole picture on disk while it's made. */
    static final int MAX_FRAMES = 600;
    private static final long TIMEOUT_SECONDS = 180;
    private static final String OUT = "out.webp";

    /** libwebp's img2webp, shipped beside gif2webp (native/build-gif2webp.sh); null when it isn't there. */
    public static Path img2webp() {
        return BundledTools.find("unichat.img2webp", null, "img2webp");
    }

    /**
     * {@code in} as an animated WebP at {@code out}: up to {@code fps} frames a second (fewer when it's long),
     * {@code side} on the longest side, lossy at {@code quality}. Blocking; an IOException with what went wrong.
     */
    public static void make(Path ffmpeg, Path img2webp, Path in, Source src, Path out, int fps, int side, int quality, double seconds)
            throws IOException, InterruptedException {
        int rate = seconds > 0 ? (int) Math.max(1, Math.min(fps, Math.floor(MAX_FRAMES / seconds))) : fps;
        // A hidden ".part" folder beside it: the cache trims one left by a crash.
        Path frames = Files.createDirectories(out.resolveSibling("." + out.getFileName() + ".frames.part"));
        try {
            run(new ProcessBuilder(frames(ffmpeg, in, src, frames, rate, side)), "ffmpeg");
            List<String> names;
            try (var list = Files.list(frames)) {
                names = list.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".pam")).sorted().toList();
            }
            if (names.isEmpty()) throw new IOException(I18n.t("video.webpFailed"));
            // Short names, run where they are: hundreds of frames as whole paths would pass Windows' command-line limit.
            run(new ProcessBuilder(webp(img2webp, names, 1000 / rate, quality)).directory(frames.toFile()), "img2webp");
            Path made = frames.resolve(OUT);
            SafeFile.at(out).write(o -> Files.copy(made, o));
        } finally {
            try (var list = Files.list(frames)) {
                for (Path f : list.toList()) Files.deleteIfExists(f);
            }
            Files.deleteIfExists(frames);
        }
    }

    private static void run(ProcessBuilder pb, String what) throws IOException, InterruptedException {
        Processes.Result r = Processes.capture(pb.redirectErrorStream(true), TIMEOUT_SECONDS);
        if (!r.ok()) {
            throw new IOException(I18n.t("video.webpFailed") + " (" + what + (r.finished() ? " exit " + r.exit() : " timed out") + ": "
                    + Processes.lastLines(r.out(), 1).strip() + ")");
        }
    }

    /** ffmpeg writing {@code in}'s frames into {@code dir}, {@code fps} a second, at most {@code side} on the longest side. */
    static List<String> frames(Path ffmpeg, Path in, Source src, Path dir, int fps, int side) {
        String s = Integer.toString(side);
        String scale = "scale=w='if(gt(iw,ih),min(" + s + ",iw),-2)':h='if(gt(iw,ih),-2,min(" + s + ",ih))':flags=lanczos";
        List<String> c = new ArrayList<>(List.of(ffmpeg.toString(), "-nostdin", "-y", "-loglevel", "error",
                "-protocol_whitelist", "file", "-f", src.format()));
        if (src.libvpx()) c.addAll(List.of("-c:v", "libvpx-vp9"));
        c.addAll(List.of("-i", in.toString(), "-an", "-vf", "fps=" + fps + "," + scale, "-frames:v", Integer.toString(MAX_FRAMES),
                "-f", "image2", "-c:v", "pam", "-pix_fmt", src.alpha() ? "rgba" : "rgb24", dir.resolve("f%05d.pam").toString()));
        return c;
    }

    /** img2webp putting the frames {@code names} together, {@code ms} each, looping, lossy at {@code quality}. */
    static List<String> webp(Path img2webp, List<String> names, int ms, int quality) {
        List<String> c = new ArrayList<>(List.of(img2webp.toString(), "-loop", "0", "-lossy", "-q", Integer.toString(quality),
                "-m", "4", "-d", Integer.toString(ms)));
        c.addAll(names);
        c.addAll(List.of("-o", OUT));
        return c;
    }

    /** What ffmpeg reads of a video: whether it has sound, and how long it lasts (0 when it doesn't say). */
    public record Probe(boolean sound, double seconds) {}

    /** {@link Probe} of {@code in}, read as {@code src} only. */
    public static Probe probe(Path ffmpeg, Path in, Source src) throws IOException, InterruptedException {
        Processes.Result r = Processes.capture(new ProcessBuilder(ffmpeg.toString(), "-nostdin", "-hide_banner",
                "-protocol_whitelist", "file", "-f", src.format(), "-i", in.toString()).redirectErrorStream(true), 60);
        Matcher d = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+(?:\\.\\d+)?)").matcher(r.out());
        double seconds = d.find() ? Integer.parseInt(d.group(1)) * 3600 + Integer.parseInt(d.group(2)) * 60 + Double.parseDouble(d.group(3)) : 0;
        return new Probe(r.out().contains(" Audio: "), seconds);
    }
}
