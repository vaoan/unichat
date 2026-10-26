package app.unichat.matrix;

import app.unichat.model.RoomFeatures;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Stickers in a format the chat refuses (Telegram's animated stickers arrive as GIF; WhatsApp only takes WebP/PNG):
 * which format to send instead, and the conversion. GIF → animated WebP uses libwebp's {@code gif2webp}, shipped
 * inside the app; without it the first frame goes out as a still PNG.
 */
final class StickerConverter {
    static final String WEBP = "image/webp", PNG = "image/png";

    record Result(Path file, String mime) {}

    private StickerConverter() {}

    /** The format to convert a {@code mime} sticker to for a chat with {@code f}; null = send it as it is. */
    static String target(String mime, RoomFeatures f) {
        if (f.isUnknown() || !f.accepts(RoomFeatures.STICKER) || f.acceptsMime(RoomFeatures.STICKER, mime)) return null;
        if (f.acceptsMime(RoomFeatures.STICKER, WEBP)) return WEBP;
        if (f.acceptsMime(RoomFeatures.STICKER, PNG)) return PNG;
        return null;
    }

    /**
     * {@code gif2webp}: {@code -Dunichat.gif2webp} (development), the copy inside the app (app/server/), or one on
     * the PATH; null when there's none.
     */
    static Path gif2webp() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String exe = windows ? "gif2webp.exe" : "gif2webp";
        String o = System.getProperty("unichat.gif2webp");
        if (o != null) return Files.isExecutable(Path.of(o)) ? Path.of(o) : null;
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher != null) {
            Path l = Path.of(launcher);
            Path app = windows ? l.getParent().resolve("app") : l.getParent().resolveSibling("app");
            Path f = app.resolve("server").resolve(exe);
            if (Files.isExecutable(f)) return f;
        }
        for (String d : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (d.isBlank()) continue;
            Path f = Path.of(d, exe);
            if (Files.isExecutable(f)) return f;
        }
        return null;
    }

    static Result convert(Path in, String target, Path dir) throws IOException {
        return convert(in, target, dir, gif2webp());
    }

    /** Converts {@code in} into {@code dir}; a WebP that can't be made (no tool, tool failed) becomes a PNG. */
    static Result convert(Path in, String target, Path dir, Path tool) throws IOException {
        Files.createDirectories(dir);
        if (WEBP.equals(target) && tool != null) {
            Path out = Files.createTempFile(dir, "sticker", ".webp");
            try {
                Process p = new ProcessBuilder(tool.toString(), "-mixed", "-q", "80", "-m", "4", in.toString(), "-o", out.toString())
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if (!p.waitFor(60, TimeUnit.SECONDS)) p.destroyForcibly();
                else if (p.exitValue() == 0 && Files.size(out) > 0) return new Result(out, WEBP);
                System.err.println("gif2webp failed (exit " + (p.isAlive() ? "timeout" : p.exitValue()) + "); sending a still sticker");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Conversión interrumpida", e);
            }
            Files.deleteIfExists(out);
        }
        BufferedImage first = ImageIO.read(in.toFile());
        if (first == null) throw new IOException("No se pudo leer el sticker");
        Path out = Files.createTempFile(dir, "sticker", ".png");
        ImageIO.write(first, "png", out.toFile());
        return new Result(out, PNG);
    }
}
