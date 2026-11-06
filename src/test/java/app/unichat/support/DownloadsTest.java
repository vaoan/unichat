package app.unichat.support;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DownloadsTest {
    private static final byte[] BODY = "contenido".getBytes(StandardCharsets.UTF_8);
    @TempDir Path dir;
    private HttpServer server;
    private String url;

    @BeforeEach
    void serve() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/f", ex -> {
            ex.sendResponseHeaders(200, BODY.length);
            try (var o = ex.getResponseBody()) {
                o.write(BODY);
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/f";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aMatchingFileArrivesWhole() throws Exception {
        Path target = dir.resolve("sub/archivo");
        List<Double> progress = new ArrayList<>();
        Downloads.fetch(url, BODY.length, Hashes.sha256("contenido"), target, "el archivo", progress::add);
        assertEquals("contenido", Files.readString(target));
        assertEquals(1.0, progress.get(progress.size() - 1));
    }

    @Test
    void aFileThatDoesntMatchLeavesNothing() {
        Path target = dir.resolve("archivo");
        assertThrows(IOException.class, () -> Downloads.fetch(url, BODY.length, Hashes.sha256("otra cosa"), target, "el archivo", p -> { }));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(dir.resolve("archivo.part")));
    }
}
