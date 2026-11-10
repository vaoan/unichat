package app.unichat.cloud;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebDavTest {
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path dir;

    @Test
    void aFileComesDownIntoAFileNeverWholeInMemory() throws Exception {
        try (FakeDav cloud = new FakeDav("u", "p")) {
            WebDav dav = new WebDav(cloud.url(), "u", "p");
            byte[] video = new byte[300_000];
            new java.util.Random(3).nextBytes(video);
            cloud.put("Unichat/Multimedia/Ana/video [1].mp4", video);
            StorageDownloads.check(dav, "Unichat/Multimedia/Ana/video [1].mp4", video, dir);
        }
    }
    @Test
    void foldersFilesAndWhetherTheyChanged() throws Exception {
        try (FakeDav cloud = new FakeDav("yo@correo.com", "clave")) {
            WebDav dav = new WebDav(cloud.url(), "yo@correo.com", "clave");
            dav.check();
            assertFalse(dav.exists("Unichat/Multimedia/Dann Serna/foto [abc].jpg"));
            dav.mkdirs("Unichat/Multimedia/Dann Serna");
            assertTrue(cloud.dirs.containsAll(java.util.List.of("Unichat", "Unichat/Multimedia", "Unichat/Multimedia/Dann Serna")));
            dav.put("Unichat/Multimedia/Dann Serna/foto [abc].jpg", "f".getBytes(StandardCharsets.UTF_8));
            assertTrue(dav.exists("Unichat/Multimedia/Dann Serna/foto [abc].jpg"), "names with spaces and brackets");
            assertArrayEquals("f".getBytes(StandardCharsets.UTF_8), dav.get("Unichat/Multimedia/Dann Serna/foto [abc].jpg"));
            assertNull(dav.get("nada.txt"));
            String before = dav.stamp("Unichat/Multimedia/Dann Serna/foto [abc].jpg");
            cloud.put("Unichat/Multimedia/Dann Serna/foto [abc].jpg", "g".getBytes(StandardCharsets.UTF_8));
            assertNotEquals(before, dav.stamp("Unichat/Multimedia/Dann Serna/foto [abc].jpg"), "a new version is told apart");
            assertNull(dav.stamp("nada.txt"));
        }
    }

    @Test
    void aWrongPasswordSaysSo() throws Exception {
        try (FakeDav cloud = new FakeDav("yo@correo.com", "clave")) {
            IOException e = assertThrows(IOException.class, () -> new WebDav(cloud.url(), "yo@correo.com", "otra").check());
            assertEquals(app.unichat.support.I18n.t("cloud.error.login"), e.getMessage());
        }
    }

    @Test
    void aServerThatNeverAnswersEndsInAFailureNotAHang() throws Exception {
        try (java.net.ServerSocket silent = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            // It takes the connection and never says a word (pCloud's WebDAV with two-step verification on did this).
            WebDav dav = new WebDav("http://127.0.0.1:" + silent.getLocalPort() + "/dav", "u", "p", java.time.Duration.ofNanos(1));
            IOException e = assertThrows(IOException.class, dav::check);
            assertEquals(app.unichat.support.I18n.t("cloud.error.noAnswer"), e.getMessage());
        }
    }

    @Test
    void itSaysAFilesSize() throws Exception {
        try (FakeDav cloud = new FakeDav("u", "p")) {
            WebDav dav = new WebDav(cloud.url(), "u", "p");
            dav.put("a.jpg", "12345".getBytes(StandardCharsets.UTF_8));
            assertEquals(new Storage.RemoteFile(5, null, null), dav.info("a.jpg"), "WebDAV gives the size, no checksum");
            assertNull(dav.info("nada.jpg"));
        }
    }
}
