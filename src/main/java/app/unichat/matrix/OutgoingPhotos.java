package app.unichat.matrix;

import app.unichat.settings.Settings;
import app.unichat.support.Folders;
import app.unichat.support.Hashes;
import app.unichat.support.ImageHeaders;
import app.unichat.video.SendablePhotos;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Pictures sent as photos ({@code SendAs.PHOTO}): the original when it already goes as one, else a JPEG made for it
 * ({@link SendablePhotos}). The copies live in {@code cache/sending} for a day, so the message shows them until the
 * server's copy arrives (and a retry finds them made); older ones go whenever another is made.
 */
final class OutgoingPhotos {
    /** What to upload, and its size from the header. */
    record Photo(Path file, String mime, int width, int height) {}

    private static final Duration KEEP = Duration.ofDays(1);

    private OutgoingPhotos() {}

    /** Blocking. A copy that can't be made sends the original: better as a file than never. */
    static Photo prepare(Path file, String mime) {
        int[] wh = ImageHeaders.size(file);
        Photo original = new Photo(file, mime, wh[0], wh[1]);
        try {
            if (SendablePhotos.alreadyPhoto(mime, wh[0], wh[1], Files.size(file))) return original;
            Path root = Settings.dataDir().resolve("cache").resolve("sending");
            prune(root);
            Path jpeg = SendablePhotos.jpeg(file, wh[0], wh[1], root.resolve(Hashes.sha256(file.toAbsolutePath().toString()).substring(0, 16)));
            int[] size = ImageHeaders.size(jpeg);
            return new Photo(jpeg, "image/jpeg", size[0], size[1]);
        } catch (IOException e) {
            System.err.println("Sending the picture as it is, no photo made: " + e.getMessage());
            return original;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return original;
        }
    }

    private static void prune(Path root) {
        if (!Files.isDirectory(root)) return;
        long before = System.currentTimeMillis() - KEEP.toMillis();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path d : dirs) {
                if (Files.getLastModifiedTime(d).toMillis() < before) Folders.deleteQuietly(d);
            }
        } catch (IOException e) {
            // Next time.
        }
    }
}
