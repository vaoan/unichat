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
}
