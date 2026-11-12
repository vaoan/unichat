package app.unichat.video;

import app.unichat.support.Downloads;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegTest {
    @TempDir Path dir;
    private HttpServer server;
    private volatile byte[] served;

    @BeforeEach
    void serve() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/z", ex -> {
            ex.sendResponseHeaders(200, served.length);
            try (var o = ex.getResponseBody()) {
                o.write(served);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        System.clearProperty("unichat.ffmpeg");
    }

    private PinnedTool build(byte[] zip, String entry) throws Exception {
        served = zip;
        return new PinnedTool("http://127.0.0.1:" + server.getAddress().getPort() + "/z", zip.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(zip)), entry);
    }

    private static byte[] zip(String name, String content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            z.putNextEntry(new ZipEntry("otro/LEEME.txt"));
            z.write("nada".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry(name));
            z.write(content.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void itInstallsOnlyThatProgramWhereItWasAskedAndTriesIt() throws Exception {
        Path target = dir.resolve("tools/ffmpeg");
        Path got = Ffmpeg.install(build(zip("carpeta/bin/ffmpeg", "#!/bin/sh\nexit 0\n"), "/bin/ffmpeg"), target, p -> { });
        assertEquals(target, got);
        assertTrue(Files.isExecutable(target));
        assertEquals(List.of("ffmpeg"), Files.list(dir.resolve("tools")).map(f -> f.getFileName().toString()).toList(), "the zip is gone");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aBuildThatDoesntRunIsNotKept() throws Exception {
        Path target = dir.resolve("tools/ffmpeg");
        assertThrows(IOException.class, () -> Ffmpeg.install(build(zip("ffmpeg", "#!/bin/sh\nexit 3\n"), "ffmpeg"), target, p -> { }));
        assertFalse(Files.exists(target));
    }

    @Test
    void aZipWithoutTheProgramSaysSoAndNothingEscapes() throws Exception {
        Path target = dir.resolve("tools/ffmpeg");
        assertThrows(IOException.class, () -> Ffmpeg.install(build(zip("../../malo", "x"), "/bin/ffmpeg"), target, p -> { }));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(dir.getParent().resolve("malo")));
    }

    @Test
    void theAutomaticInstallFollowsTheSetting() throws Exception {
        System.setProperty("unichat.ffmpeg", dir.resolve("no-existe").toString());
        Ffmpeg.setAutoInstall(false);
        try {
            assertNull(Ffmpeg.find());
            assertNull(Ffmpeg.whenNeeded(p -> { }), "off: nothing is downloaded");
            assertThrows(WebmStickers.NoConverter.class, () -> WebmStickers.toWebp(Files.writeString(dir.resolve("s.webm"), "x"), dir.resolve("s.webp"), p -> { }));
        } finally {
            Ffmpeg.setAutoInstall(true);
        }
    }

    @Test
    void theDownloadKeepsWorkingForWhisperToo() throws Exception {
        byte[] body = zip("a", "b");
        PinnedTool b = build(body, "a");
        Downloads.fetch(b.url(), b.size(), b.sha256(), dir.resolve("m.bin"), "el modelo de voz", p -> { });
        assertEquals(body.length, Files.size(dir.resolve("m.bin")));
    }
}
