package app.unichat.assist;

import app.unichat.support.SafeFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The versions of {@code writing-style.md} that learning replaced ({@code writing-style-history/}, one timestamped file
 * each, the newest {@link #KEEP}), so what it learned last can be seen and undone. This computer's only: the cloud
 * carries {@code writing-style.md} alone. A version an undo took back is kept too, marked as undone, and is never
 * what the next undo goes back to.
 */
final class StyleHistory {
    static final int KEEP = 20;
    static final String FOLDER = "writing-style-history";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);
    private static final String LEARNED = ".md", UNDONE = ".undone.md";

    /** One kept version: {@code when} it was replaced (or undone). */
    record Version(Path file, Instant when, boolean undone) {
        String text() {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }
    }

    private final Path dir;
    private final Clock clock;

    StyleHistory(Path dir, Clock clock) {
        this.dir = dir;
        this.clock = clock;
    }

    /** {@code text} was what learning replaced. */
    void replaced(String text) { keep(text, LEARNED); }

    /** {@code text} was what an undo took back. */
    void undone(String text) { keep(text, UNDONE); }

    /** Oldest first. */
    List<Version> versions() {
        if (!Files.isDirectory(dir)) return List.of();
        List<Version> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(f -> {
                Version v = parse(f);
                if (v != null) out.add(v);
            });
        } catch (IOException e) {
            return List.of();
        }
        out.sort(Comparator.comparing(Version::when));
        return out;
    }

    /** The newest version learning replaced (not one undone), or null. */
    Version lastLearned() {
        List<Version> all = versions();
        for (int i = all.size() - 1; i >= 0; i--) if (!all.get(i).undone()) return all.get(i);
        return null;
    }

    void remove(Version v) {
        try {
            Files.deleteIfExists(v.file());
        } catch (IOException e) {
            System.err.println("Could not remove a writing style version: " + e.getClass().getSimpleName());
        }
    }

    void clear() { versions().forEach(this::remove); }

    private void keep(String text, String suffix) {
        Instant when = clock.instant();
        // Two in the same millisecond (or a clock gone back) still get their own name, after the newest.
        List<Version> all = versions();
        if (!all.isEmpty() && !when.isAfter(all.get(all.size() - 1).when())) when = all.get(all.size() - 1).when().plusMillis(1);
        try {
            SafeFile.at(dir.resolve("writing-style-" + STAMP.format(when) + suffix), ".part").write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("Could not keep the previous writing style: " + e.getClass().getSimpleName());
            return;
        }
        List<Version> now = versions();
        for (int i = 0; i < now.size() - KEEP; i++) remove(now.get(i));
    }

    private static Version parse(Path f) {
        String name = f.getFileName().toString();
        if (!name.startsWith("writing-style-") || !name.endsWith(LEARNED)) return null;
        boolean undone = name.endsWith(UNDONE);
        String stamp = name.substring("writing-style-".length(), name.length() - (undone ? UNDONE : LEARNED).length());
        try {
            return new Version(f, Instant.from(STAMP.parse(stamp)), undone);
        } catch (DateTimeParseException e) {
            return null; // not one of ours
        }
    }
}
