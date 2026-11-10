package app.unichat.autosave;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SavedMoveTest {
    @TempDir
    Path dir;

    @Test
    void everythingGoesAlongAndNothingIsOverwritten() throws Exception {
        Path from = Files.createDirectories(dir.resolve("old/Dann Serna/links"));
        Files.writeString(from.getParent().resolve("image.jpg"), "vieja");
        Files.writeString(from.resolve("video.mp4"), "v");
        Files.writeString(from.getParent().resolve(".DS_Store"), "finder");
        Path to = Files.createDirectories(dir.resolve("new/Dann Serna"));
        Files.writeString(to.resolve("image.jpg"), "ya estaba");

        assertEquals(2, SavedMove.move(from.getParent(), to));
        assertEquals("ya estaba", Files.readString(to.resolve("image.jpg")), "what was there stays");
        assertEquals("vieja", Files.readString(to.resolve("image (2).jpg")), "and the moved one beside it");
        assertTrue(Files.exists(to.resolve("links/video.mp4")), "folders merged");
        assertFalse(Files.exists(from.getParent()), "the old folder goes once empty (Finder's file aside)");
    }

    @Test
    void neverIntoItselfNorFromNothing() throws Exception {
        Path a = Files.createDirectories(dir.resolve("a"));
        Files.writeString(a.resolve("x.jpg"), "x");
        assertEquals(0, SavedMove.move(a, a.resolve("inside")));
        assertEquals(0, SavedMove.move(a.resolve("inside"), a));
        assertEquals(0, SavedMove.move(dir.resolve("missing"), dir.resolve("b")));
        assertTrue(Files.exists(a.resolve("x.jpg")));
    }
}
