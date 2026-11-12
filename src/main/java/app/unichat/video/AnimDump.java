package app.unichat.video;

import app.unichat.support.BundledTools;
import app.unichat.support.Folders;
import app.unichat.support.Processes;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Every frame of an animated WebP, composited by libwebp's {@code anim_dump} (shipped in app/server, beside gif2webp): as a
 * browser shows it. The Java decoder (TwelveMonkeys) gets some lossy frames' colours wrong, which showed as black
 * blotches moving over a sticker.
 */
public final class AnimDump {
    private static final long TIMEOUT_SECONDS = 30;

    private AnimDump() {}

    /** {@code -Dunichat.animDump} (development), the copy inside the app, or one on the PATH; null when there's none. */
    public static Path find() {
        return BundledTools.find("unichat.animDump", null, "anim_dump");
    }

    /** The frames, oldest first, each the whole canvas; null when there's no anim_dump or it can't read the file. */
    public static List<BufferedImage> frames(Path webp) {
        return frames(webp, f -> f);
    }

    /**
     * The frames, oldest first, each made {@code each} (smaller) as it's read: a 512 px sticker's frames all whole at
     * once (1 MB each) were the peak that filled the memory when the sticker panel opened. Null as {@link #frames(Path)}.
     */
    public static List<BufferedImage> frames(Path webp, java.util.function.UnaryOperator<BufferedImage> each) {
        Path tool = find();
        if (tool == null) return null;
        Path dir = null;
        try {
            dir = Files.createTempDirectory("unichat-anim");
            Processes.Result r = Processes.capture(List.of(tool.toString(), "-folder", dir.toString(), "-prefix", "f", "-pam",
                    webp.toAbsolutePath().toString()), TIMEOUT_SECONDS);
            if (!r.finished() || r.exit() != 0) return null;
            List<Path> files;
            try (Stream<Path> s = Files.list(dir)) {
                files = s.filter(p -> p.getFileName().toString().endsWith(".pam")).sorted().toList();
            }
            if (files.isEmpty()) return null;
            List<BufferedImage> out = new ArrayList<>();
            for (Path f : files) out.add(each.apply(readPam(f)));
            return out;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (dir != null) Folders.deleteQuietly(dir);
        }
    }

    /** A PAM file as libwebp writes it: RGB_ALPHA, 8 bits per sample. */
    static BufferedImage readPam(Path f) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(f))) {
            int width = -1, height = -1, depth = -1;
            for (String line = line(in); !"ENDHDR".equals(line); line = line(in)) {
                if (line == null) throw new IOException("not a PAM file");
                String[] kv = line.strip().split("\\s+", 2);
                if (kv.length < 2) continue;
                switch (kv[0]) {
                    case "WIDTH" -> width = Integer.parseInt(kv[1]);
                    case "HEIGHT" -> height = Integer.parseInt(kv[1]);
                    case "DEPTH" -> depth = Integer.parseInt(kv[1]);
                    default -> { }
                }
            }
            if (width <= 0 || height <= 0 || (depth != 3 && depth != 4)) throw new IOException("unexpected PAM header");
            byte[] px = in.readNBytes(width * height * depth);
            if (px.length < width * height * depth) throw new IOException("short PAM file");
            int[] argb = new int[width * height];
            for (int i = 0, p = 0; i < argb.length; i++, p += depth) {
                int a = depth == 4 ? px[p + 3] & 0xFF : 0xFF;
                argb[i] = a << 24 | (px[p] & 0xFF) << 16 | (px[p + 1] & 0xFF) << 8 | (px[p + 2] & 0xFF);
            }
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, width, height, argb, 0, width);
            return img;
        }
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int c = in.read(); c != '\n'; c = in.read()) {
            if (c < 0) return b.isEmpty() ? null : b.toString();
            b.append((char) c);
        }
        return b.toString();
    }
}
