package app.unichat.service;

import app.unichat.settings.Settings;
import app.unichat.support.I18n;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** The files behind demo mode's media, made once in the cache folder. */
final class DemoMedia {
    private DemoMedia() {}

    /** The demo voice note (a 2 s tone, no speech), copied out of the app's resources. */
    static Path demoAudio(String name) throws IOException {
        Path dir = Settings.get().cacheDir().resolve("demo");
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        if (Files.exists(file)) return file;
        try (var in = DemoMedia.class.getResourceAsStream("/app/unichat/demo/" + name)) {
            if (in == null) throw new IOException(I18n.t("demo.missingAudio", name));
            Files.copy(in, file);
        }
        return file;
    }

    /** Renders a placeholder "photo" (gradient + caption) once and caches it. */
    static Path demoImage(String name) throws IOException {
        Path dir = Settings.get().cacheDir().resolve("demo");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".png");
        if (Files.exists(file)) return file;
        int w = 640, h = 420;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int seed = Math.abs(name.hashCode());
        Color a = Color.getHSBColor((seed % 360) / 360f, 0.55f, 0.95f);
        Color b = Color.getHSBColor(((seed / 7) % 360) / 360f, 0.65f, 0.55f);
        g.setPaint(new GradientPaint(0, 0, a, w, h, b));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(255, 255, 255, 60));
        for (int i = 0; i < 6; i++) {
            int r = 60 + (seed >> i) % 120;
            g.fillOval((seed >> (i + 2)) % w, (seed >> (i + 4)) % h, r, r);
        }
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
        g.drawString(name.replace('-', ' '), 32, h - 36);
        g.dispose();
        ImageIO.write(img, "png", file.toFile());
        return file;
    }
}
