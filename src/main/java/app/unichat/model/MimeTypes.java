package app.unichat.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Media types, as bridges and servers write them, and the file extensions that go with them. */
public final class MimeTypes {
    /** What a file nobody can name is sent as. */
    public static final String UNKNOWN = "application/octet-stream";

    /** A type, the extension a file of it gets, and other extensions that mean it too. */
    private record Row(String type, String extension, String... more) {}

    /** The one table: a type's first row gives its extension, an extension's first row gives its type. */
    private static final List<Row> TABLE = List.of(
            new Row("image/jpeg", ".jpg", ".jpeg"),
            new Row("image/png", ".png"),
            new Row("image/gif", ".gif"),
            new Row("image/webp", ".webp"),
            new Row("image/bmp", ".bmp"),
            new Row("image/svg+xml", ".svg"),
            new Row("video/mp4", ".mp4"),
            new Row("video/webm", ".webm"),
            new Row("video/quicktime", ".mov"),
            new Row("video/x-m4v", ".m4v"),
            new Row("video/x-matroska", ".mkv"),
            new Row("audio/ogg", ".ogg"),
            new Row("audio/mpeg", ".mp3"),
            new Row("audio/mp4", ".m4a"),
            new Row("audio/x-m4a", ".m4a"),
            new Row("audio/wav", ".wav"),
            new Row("audio/x-wav", ".wav"),
            new Row("application/pdf", ".pdf"));

    private MimeTypes() {}

    /** The extension for a type, whatever parameters it carries ({@code audio/ogg; codecs=opus}); "" when unknown. */
    public static String extension(String mimeType) {
        if (mimeType == null) return "";
        int parameters = mimeType.indexOf(';');
        String type = (parameters < 0 ? mimeType : mimeType.substring(0, parameters)).strip().toLowerCase(Locale.ROOT);
        return TABLE.stream().filter(r -> r.type.equals(type)).map(Row::extension).findFirst().orElse("");
    }

    /** The type a file's name says it is, whatever its case; null when the extension is unknown or missing. */
    public static String ofFileName(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        if (dot < 0) return null;
        String ext = name.substring(dot).toLowerCase(Locale.ROOT);
        return TABLE.stream().filter(r -> r.extension.equals(ext) || List.of(r.more).contains(ext))
                .map(Row::type).findFirst().orElse(null);
    }

    /** What the system says a file is, else what its name says, else {@link #UNKNOWN}. */
    public static String of(Path file) {
        try {
            String probed = Files.probeContentType(file);
            if (probed != null) return probed;
        } catch (IOException | RuntimeException ignored) {
            // By name below.
        }
        Path name = file.getFileName();
        String byName = name == null ? null : ofFileName(name.toString());
        return byName == null ? UNKNOWN : byName;
    }
}
