package app.unichat.ui.media;

import app.unichat.settings.Settings;
import app.unichat.support.BackgroundWriter;
import app.unichat.support.SafeFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The shape (width / height) of each picture in messages' HTML once seen, kept across restarts in
 * {@code cache/picture-shapes.tsv}: its size isn't known before it loads, so its box started as 16:9 and grew when the
 * picture came, moving the chat, each time the app was opened again. The newest {@link #KEPT} only.
 */
public final class PictureShapes {
    static final int KEPT = 4000;
    private static PictureShapes shared;

    private final Path file;
    private final Map<String, Double> shapes = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Double> e) { return size() > KEPT; }
    };
    private final BackgroundWriter writer;

    PictureShapes(Path file) {
        this.file = file;
        this.writer = new BackgroundWriter("picture-shapes", this, this::save);
        try {
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    int tab = line.lastIndexOf('\t');
                    if (tab > 0) shapes.put(line.substring(0, tab), Double.parseDouble(line.substring(tab + 1)));
                }
            }
        } catch (IOException | NumberFormatException e) {
            System.err.println("Picture shapes not read (they're only a cache): " + e);
        }
    }

    public static synchronized PictureShapes get() {
        if (shared == null) shared = new PictureShapes(Settings.get().cacheDir().resolve("picture-shapes.tsv"));
        return shared;
    }

    /** What {@code url}'s picture looked like last time, else {@code unknown}. */
    public synchronized double shape(String url, double unknown) {
        Double s = shapes.get(url);
        return s == null ? unknown : s;
    }

    /** {@code url}'s picture turned out {@code shape} wide per unit of height; saved in the background. */
    public void remember(String url, double shape) {
        if (!(shape > 0) || Double.isInfinite(shape)) return;
        synchronized (this) {
            Double was = shapes.put(url, shape);
            if (was != null && Math.abs(was - shape) < 1e-6) return;
        }
        writer.request();
    }

    void flush() {
        writer.flush();
    }

    private void save() {
        StringBuilder out = new StringBuilder();
        synchronized (this) {
            shapes.forEach((url, s) -> out.append(url).append('\t').append(s).append('\n'));
        }
        try {
            SafeFile.replace(file, out.toString().getBytes(StandardCharsets.UTF_8), false);
        } catch (IOException e) {
            System.err.println("Picture shapes not saved (they're only a cache): " + e);
        }
    }
}
