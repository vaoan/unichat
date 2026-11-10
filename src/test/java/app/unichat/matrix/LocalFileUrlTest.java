package app.unichat.matrix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A message's media address is the sender's to choose: a {@code file:} one from someone else must never name a file of
 * this computer (copied by autosave to the cloud, opened, or on Windows an SMB path that leaks the login's hash).
 */
class LocalFileUrlTest {
    @Test
    void aFileAddressFromAMessageIsRefusedAndOnlyWhatWeSentIsOurs(@TempDir Path dir) throws Exception {
        Path secret = Files.writeString(dir.resolve("id_rsa"), "secret");
        Path sent = Files.writeString(dir.resolve("photo.jpg"), "jpeg");
        try (ExecutorService io = Executors.newSingleThreadExecutor()) {
            var media = new MediaDownloader(null, io);
            assertThrows(ExecutionException.class, () -> media.media(secret.toUri().toString(), 0, 0).get());
            assertThrows(ExecutionException.class, () -> media.media("file://attacker.example/share/x.png", 0, 0).get());

            media.ownFile(sent);
            assertEquals(sent, media.media(sent.toUri().toString(), 0, 0).get());
            assertThrows(ExecutionException.class, () -> media.media(secret.toUri().toString(), 0, 0).get());
        }
    }
}
