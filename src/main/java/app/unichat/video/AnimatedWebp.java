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

    /**
     * The most frames one may have, about 30 s at 30 fps (a longer one gets fewer a second instead): each is a whole
     * picture on disk while it's made.
     */
    static final int MAX_FRAMES = 900;
    /** For {@link #make}'s rate or side: as the video has it. */
    public static final int AS_IT_COMES = 0;
    private static final long TIMEOUT_SECONDS = 180;
    private static final String OUT = "out.webp";

    /** libwebp's img2webp, shipped beside gif2webp (native/build-gif2webp.sh); null when it isn't there. */
    public static Path img2webp() {
        return BundledTools.find("unichat.img2webp", null, "img2webp");
    }

    /**
     * {@code in} as an animated WebP at {@code out}: up to {@code fps} frames a second and {@code side} on the longest
     * side ({@link #AS_IT_COMES}: as the video has them; a very long one gets fewer frames a second), lossy at
     * {@code quality}. Blocking; an IOException with what went wrong.
     */
    public static void make(Path ffmpeg, Path img2webp, Path in, Source src, Path out, int fps, int side, int quality, Probe probe)
            throws IOException, InterruptedException {
        int rate = rate(fps, probe);
        double seconds = probe.seconds();
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
            int ms = rate != AS_IT_COMES ? 1000 / rate : seconds > 0 ? (int) Math.round(seconds * 1000 / names.size()) : 40;
            run(new ProcessBuilder(webp(img2webp, names, ms, quality)).directory(frames.toFile()), "img2webp");
            Path made = frames.resolve(OUT);
            SafeFile.at(out).write(o -> Files.copy(made, o));
        } finally {
            try (var list = Files.list(frames)) {
                for (Path f : list.toList()) Files.deleteIfExists(f);
            }
            Files.deleteIfExists(frames);
        }
    }

    /**
     * The frames a second to take: {@code fps}, or as they come ({@link #AS_IT_COMES}); fewer only when that many would
     * pass {@link #MAX_FRAMES} (a video's own rate is 30 when it doesn't say).
     */
    static int rate(int fps, Probe probe) {
        if (probe.seconds() <= 0) return fps;
        double natural = fps != AS_IT_COMES ? fps : probe.fps() > 0 ? probe.fps() : 30;
        if (probe.seconds() * natural <= MAX_FRAMES) return fps;
        return (int) Math.max(1, Math.floor(MAX_FRAMES / probe.seconds()));
    }

    private static void run(ProcessBuilder pb, String what) throws IOException, InterruptedException {
        Processes.Result r = Processes.capture(pb.redirectErrorStream(true), TIMEOUT_SECONDS);
        if (!r.ok()) {
            throw new IOException(I18n.t("video.webpFailed") + " (" + what + (r.finished() ? " exit " + r.exit() : " timed out") + ": "
                    + Processes.lastLines(r.out(), 1).strip() + ")");
        }
    }

    /** ffmpeg writing {@code in}'s frames into {@code dir}: {@code fps} a second and at most {@code side} on the longest side, or as they come. */
    static List<String> frames(Path ffmpeg, Path in, Source src, Path dir, int fps, int side) {
        String s = Integer.toString(side);
        List<String> filters = new ArrayList<>();
        if (fps != AS_IT_COMES) filters.add("fps=" + fps);
        if (side != AS_IT_COMES) filters.add("scale=w='if(gt(iw,ih),min(" + s + ",iw),-2)':h='if(gt(iw,ih),-2,min(" + s + ",ih))':flags=lanczos");
        List<String> c = new ArrayList<>(List.of(ffmpeg.toString(), "-nostdin", "-y", "-loglevel", "error",
                "-protocol_whitelist", "file", "-f", src.format()));
        if (src.libvpx()) c.addAll(List.of("-c:v", "libvpx-vp9"));
        c.addAll(List.of("-i", in.toString(), "-an"));
        if (!filters.isEmpty()) c.addAll(List.of("-vf", String.join(",", filters)));
        // As it comes: a variable rate keeps each frame once (no copies to fill a fixed rate).
        c.addAll(List.of("-fps_mode", "passthrough", "-frames:v", Integer.toString(MAX_FRAMES),
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

    /** What ffmpeg reads of a video: whether it has sound, how long it lasts, its frames a second and size (0 when it doesn't say). */
    public record Probe(boolean sound, double seconds, double fps, int width, int height) {
        public Probe(boolean sound, double seconds, double fps) {
            this(sound, seconds, fps, 0, 0);
        }
    }

    /** {@link Probe} of {@code in}, read as {@code src} only. */
    public static Probe probe(Path ffmpeg, Path in, Source src) throws IOException, InterruptedException {
        Processes.Result r = Processes.capture(new ProcessBuilder(ffmpeg.toString(), "-nostdin", "-hide_banner",
                "-protocol_whitelist", "file", "-f", src.format(), "-i", in.toString()).redirectErrorStream(true), 60);
        Matcher d = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+(?:\\.\\d+)?)").matcher(r.out());
        double seconds = d.find() ? Integer.parseInt(d.group(1)) * 3600 + Integer.parseInt(d.group(2)) * 60 + Double.parseDouble(d.group(3)) : 0;
        Matcher f = Pattern.compile("([0-9]+(?:\\.[0-9]+)?) fps").matcher(r.out());
        Matcher wh = Pattern.compile("Video: .*?, ([0-9]{2,5})x([0-9]{2,5})").matcher(r.out());
        boolean sized = wh.find();
        return new Probe(r.out().contains(" Audio: "), seconds, f.find() ? Double.parseDouble(f.group(1)) : 0,
                sized ? Integer.parseInt(wh.group(1)) : 0, sized ? Integer.parseInt(wh.group(2)) : 0);
    }
}
