package app.unichat.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaLibraryTest {
    @TempDir Path dir;

    private static MediaLibrary.Item sticker(String url) {
        return new MediaLibrary.Item(MediaLibrary.Kind.STICKER, url, "image/webp", 512, 512, null);
    }

    @Test
    void recentsAreNewestFirstUniqueAndCapped() {
        MediaLibrary lib = new MediaLibrary(dir.resolve("lib.json"));
        for (int i = 0; i < 70; i++) lib.addRecent(sticker("mxc://s/" + i));
        lib.addRecent(sticker("mxc://s/5"));
        List<MediaLibrary.Item> r = lib.recents(MediaLibrary.Kind.STICKER);
        assertEquals(60, r.size());
        assertEquals("mxc://s/5", r.get(0).url(), "re-used goes to the front");
        assertEquals(1, r.stream().filter(i -> i.url().equals("mxc://s/5")).count(), "no duplicates");
        assertTrue(lib.recents(MediaLibrary.Kind.GIF).isEmpty());
    }

    @Test
    void favouritesToggleAndSurviveReload() {
        Path f = dir.resolve("lib.json");
        MediaLibrary lib = new MediaLibrary(f);
        assertTrue(lib.toggleFavorite(sticker("mxc://s/a")));
        assertTrue(lib.isFavorite("mxc://s/a"));
        lib.addRecent(new MediaLibrary.Item(MediaLibrary.Kind.GIF, "mxc://s/g", "video/mp4", 320, 240, null));
        MediaLibrary again = new MediaLibrary(f);
        assertEquals("mxc://s/a", again.favorites(MediaLibrary.Kind.STICKER).get(0).url());
        assertEquals(1, again.recents(MediaLibrary.Kind.GIF).size());
        assertFalse(again.toggleFavorite(sticker("mxc://s/a")), "second toggle removes it");
        assertTrue(new MediaLibrary(f).favorites(MediaLibrary.Kind.STICKER).isEmpty());
    }

    @Test
    void corruptFileMeansAnEmptyLibrary() throws Exception {
        Path f = dir.resolve("lib.json");
        Files.writeString(f, "{not json");
        MediaLibrary lib = new MediaLibrary(f);
        assertTrue(lib.recents(MediaLibrary.Kind.STICKER).isEmpty());
        lib.addRecent(sticker("mxc://s/x"));
        assertEquals(1, new MediaLibrary(f).recents(MediaLibrary.Kind.STICKER).size(), "and it heals on the next save");
    }

    @Test
    void seenStickersAreRememberedWithoutReordering() {
        MediaLibrary lib = new MediaLibrary(dir.resolve("lib.json"));
        lib.addRecent(sticker("mxc://s/a"));
        lib.addRecent(sticker("mxc://s/b"));
        lib.rememberSeen(sticker("mxc://s/a"));
        assertEquals("mxc://s/b", lib.recents(MediaLibrary.Kind.STICKER).get(0).url(), "scrolling past a sticker doesn't reorder");
        lib.rememberSeen(sticker("mxc://s/c"));
        assertEquals("mxc://s/c", lib.recents(MediaLibrary.Kind.STICKER).get(0).url(), "a new one is added");
    }
}
