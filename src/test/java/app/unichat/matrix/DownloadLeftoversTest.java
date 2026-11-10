package app.unichat.matrix;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A download cut short leaves nothing behind: no {@code .part} file, no watcher of its progress. */
class DownloadLeftoversTest {
    @TempDir
    Path dir;
    private ServerSocket server;

    @BeforeEach
    void serverThatHangsUpHalfway() throws Exception {
        // Promises 100 kB, sends 10 and closes the connection: what a network that goes away does. A raw socket,
        // since HttpServer keeps the connection open after a short body and the client would wait for the rest.
        server = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().start(() -> {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    s.getInputStream().read(new byte[8192]);
                    OutputStream out = s.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.write(new byte[10_000]);
                    out.flush();
                } catch (IOException closed) {
                    // the test is over, or the client went first
                }
            }
        });
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
    }

    @Test
    void aDownloadCutShortDeletesItsPartFile() throws Exception {
        MatrixClient client = new MatrixClient("http://127.0.0.1:" + server.getLocalPort(), "tok");
        Path target = dir.resolve("photo.jpg");
        assertThrows(IOException.class, () -> client.download("/media", true, target));
        assertThrows(IOException.class, () -> client.download("/media", true, target, p -> { }));
        try (Stream<Path> left = Files.list(dir)) {
            assertEquals(List.of(), left.toList());
        }
    }

    @Test
    void nobodyIsLeftWatchingAFinishedDownload() throws Exception {
        try (ExecutorService io = Executors.newSingleThreadExecutor()) {
            var media = new MediaDownloader(null, io);
            var f = media.media("file:///not/ours.png", 0, 0, p -> { });
            assertThrows(ExecutionException.class, f::get);
            assertEquals(0, media.watched(), "the key of a download no one waits for any more");
        }
    }
}
