package app.unichat.server;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryStoreTest {
    private static final byte[] BINARY = "#!/bin/sh\necho bridge\n".getBytes(StandardCharsets.UTF_8);
    private static final Manifest.Component WA = new Manifest.Component("whatsapp", "mautrix/whatsapp", "v1", "mautrix-whatsapp");

    @TempDir Path dir;
    private HttpServer server;
    private final AtomicInteger downloads = new AtomicInteger();
    private volatile String publishedSum;

    @BeforeEach
    void fakeReleases() throws Exception {
        publishedSum = sha(BINARY);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            byte[] body;
            if (path.endsWith("/sha256sums.txt")) {
                body = (publishedSum + "  mautrix-whatsapp-darwin-arm64\nffff  other\n").getBytes(StandardCharsets.UTF_8);
            } else if (path.endsWith("/mautrix-whatsapp-darwin-arm64")) {
                downloads.incrementAndGet();
                body = BINARY;
            } else {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private BinaryStore store() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new BinaryStore(dir, Platform.MAC_ARM64, c -> base + "/" + c.repo() + "/" + c.tag());
    }

    @Test
    void installsVerifiedExecutableOnce() throws Exception {
        BinaryStore store = store();
        assertFalse(store.isInstalled(WA));
        Path bin = store.install(WA);
        assertArrayEquals(BINARY, Files.readAllBytes(bin));
        assertTrue(Files.isExecutable(bin));
        assertTrue(store.isInstalled(WA));
        store.install(WA);
        assertEquals(1, downloads.get(), "already installed at that version: no second download");
    }

    @Test
    void newVersionInManifestIsDownloadedAgain() throws Exception {
        store().install(WA);
        Manifest.Component newer = new Manifest.Component("whatsapp", "mautrix/whatsapp", "v2", "mautrix-whatsapp");
        assertFalse(store().isInstalled(newer));
        store().install(newer);
        assertEquals(2, downloads.get());
    }

    @Test
    void checksumMismatchInstallsNothing() {
        publishedSum = "0".repeat(64);
        IOException e = assertThrows(IOException.class, () -> store().install(WA));
        assertTrue(e.getMessage().contains("checksum"), e.getMessage());
        assertFalse(Files.exists(dir.resolve("whatsapp")));
        assertFalse(Files.exists(dir.resolve("whatsapp.part")));
    }

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }
}
