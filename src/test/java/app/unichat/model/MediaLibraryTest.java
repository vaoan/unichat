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

    @Test
    void emojisAreFavouritesTooAndKeptApart() {
        Path f = dir.resolve("lib.json");
        MediaLibrary lib = new MediaLibrary(f);
        assertTrue(lib.toggleFavorite(MediaLibrary.emoji("🔥")));
        lib.toggleFavorite(sticker("mxc://s/a"));
        MediaLibrary again = new MediaLibrary(f);
        assertEquals("🔥", again.favorites(MediaLibrary.Kind.EMOJI).get(0).url());
        assertEquals(1, again.favorites(MediaLibrary.Kind.STICKER).size());
        assertTrue(again.favorites(MediaLibrary.Kind.GIF).isEmpty());
    }

    @Test
    void aRecentCanBeRemovedAndChangesAreAnnounced() {
        MediaLibrary lib = new MediaLibrary(dir.resolve("lib.json"));
        int[] changes = {0};
        lib.addListener(() -> changes[0]++);
        lib.addRecent(sticker("mxc://s/a"));
        assertTrue(lib.isRecent("mxc://s/a"));
        lib.removeRecent("mxc://s/a");
        assertTrue(lib.recents(MediaLibrary.Kind.STICKER).isEmpty());
        lib.removeRecent("mxc://s/a");
        assertEquals(2, changes[0], "removing what isn't there changes nothing");
    }

    @Test
    void aKindFromANewerVersionDoesNotWipeTheLibrary() throws Exception {
        Path f = dir.resolve("lib.json");
        Files.writeString(f, "{\"favorites\":[{\"kind\":\"HOLOGRAM\",\"url\":\"x\"},"
                + "{\"kind\":\"STICKER\",\"url\":\"mxc://s/a\"}],\"recents\":[]}");
        assertEquals(1, new MediaLibrary(f).favorites(MediaLibrary.Kind.STICKER).size());
    }

    private static MediaLibrary.Item telegram(String url, String id, int size) {
        var info = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("size", size);
        if (id != null) info.putObject("fi.mau.bridged_sticker").put("network", "telegram").put("id", id);
        return new MediaLibrary.Item(MediaLibrary.Kind.STICKER, url, "image/webp", 256, 256, info);
    }

    @Test
    void theSameStickerUnderAnotherUrlIsOneRecent() {
        MediaLibrary lib = new MediaLibrary(dir.resolve("lib.json"));
        lib.addRecent(telegram("mxc://t/1", "doc-1", 100));
        lib.addRecent(telegram("mxc://t/2", "doc-2", 200));
        lib.addRecent(telegram("mxc://t/3", "doc-1", 100));
        List<MediaLibrary.Item> r = lib.recents(MediaLibrary.Kind.STICKER);
        assertEquals(List.of("mxc://t/3", "mxc://t/2"), r.stream().map(MediaLibrary.Item::url).toList(), "the newest address stays");
    }

    @Test
    void seeingACopyDoesNotMoveOrRepeatIt() {
        MediaLibrary lib = new MediaLibrary(dir.resolve("lib.json"));
        lib.addRecent(telegram("mxc://t/1", "doc-1", 100));
        lib.addRecent(telegram("mxc://t/2", "doc-2", 200));
        lib.rememberSeen(telegram("mxc://t/9", "doc-1", 100));
        assertEquals(List.of("mxc://t/2", "mxc://t/1"), lib.recents(MediaLibrary.Kind.STICKER).stream().map(MediaLibrary.Item::url).toList());
    }

    @Test
    void withoutAnIdTheSameBytesAreTheSameSticker() {
        MediaLibrary lib = new MediaLibrary(dir.resolve("lib.json"));
        lib.addRecent(telegram("mxc://t/1", "doc-1", 100));
        lib.addRecent(telegram("mxc://localhost/1", null, 100));
        assertEquals(1, lib.recents(MediaLibrary.Kind.STICKER).size(), "the bridge's copy and a stored copy");
    }

    @Test
    void differentIdsAreDifferentStickersEvenWithTheSameSize() {
        MediaLibrary lib = new MediaLibrary(dir.resolve("lib.json"));
        lib.addRecent(telegram("mxc://t/1", "doc-1", 100));
        lib.addRecent(telegram("mxc://t/2", "doc-2", 100));
        assertEquals(2, lib.recents(MediaLibrary.Kind.STICKER).size());
    }

    @Test
    void withNoSizeOnlyTheUrlCounts() {
        MediaLibrary lib = new MediaLibrary(dir.resolve("lib.json"));
        lib.addRecent(sticker("mxc://s/a"));
        lib.addRecent(sticker("mxc://s/b"));
        assertEquals(2, lib.recents(MediaLibrary.Kind.STICKER).size());
    }

    @Test
    void aFavoriteIsRecognisedAndRemovedUnderAnotherUrl() {
        MediaLibrary lib = new MediaLibrary(dir.resolve("lib.json"));
        lib.toggleFavorite(telegram("mxc://t/1", "doc-1", 100));
        assertTrue(lib.isFavorite(telegram("mxc://t/2", "doc-1", 100)));
        assertFalse(lib.toggleFavorite(telegram("mxc://t/2", "doc-1", 100)), "toggling the copy removes it");
        assertTrue(lib.favorites(MediaLibrary.Kind.STICKER).isEmpty());
    }

    @Test
    void removingARecentRemovesItsCopiesToo() {
        MediaLibrary lib = new MediaLibrary(dir.resolve("lib.json"));
        lib.addRecent(telegram("mxc://t/1", "doc-1", 100));
        assertTrue(lib.isRecent(telegram("mxc://t/2", "doc-1", 100)));
        lib.removeRecent(telegram("mxc://t/2", "doc-1", 100));
        assertTrue(lib.recents(MediaLibrary.Kind.STICKER).isEmpty());
    }

    @Test
    void copiesAlreadySavedAreCleanedOnOpeningAndTheFileIsFixed() throws Exception {
        Path f = dir.resolve("lib.json");
        String one = "{\"kind\":\"STICKER\",\"url\":\"mxc://t/%s\",\"mime\":\"image/webp\",\"width\":256,\"height\":256,"
                + "\"info\":{\"size\":100,\"fi.mau.bridged_sticker\":{\"id\":\"doc-1\"}}}";
        Files.writeString(f, "{\"favorites\":[" + one.formatted("f1") + "," + one.formatted("f2") + "],\"recents\":["
                + one.formatted("new") + "," + one.formatted("old") + "]}");
        MediaLibrary lib = new MediaLibrary(f);
        assertEquals(List.of("mxc://t/new"), lib.recents(MediaLibrary.Kind.STICKER).stream().map(MediaLibrary.Item::url).toList());
        assertEquals(List.of("mxc://t/f1"), lib.favorites(MediaLibrary.Kind.STICKER).stream().map(MediaLibrary.Item::url).toList());
        assertFalse(Files.readString(f).contains("mxc://t/old"), "saved without them");
    }

    @Test
    void aGifAndAStickerAreNeverTheSame() {
        MediaLibrary lib = new MediaLibrary(dir.resolve("lib.json"));
        lib.addRecent(telegram("mxc://t/1", null, 100));
        var info = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("size", 100);
        lib.addRecent(new MediaLibrary.Item(MediaLibrary.Kind.GIF, "mxc://t/2", "image/webp", 256, 256, info));
        assertEquals(1, lib.recents(MediaLibrary.Kind.STICKER).size());
        assertEquals(1, lib.recents(MediaLibrary.Kind.GIF).size());
    }

    @Test
    void theQuickReactionsAreTheOnesUsedMostAndTravelInTheFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        java.nio.file.Path f = dir.resolve("media-library.json");
        MediaLibrary lib = new MediaLibrary(f);
        java.util.List<String> usual = java.util.List.of("❤️", "👍", "😂", "😮", "😢", "🙏");
        assertEquals(usual, lib.mostReacted(6, usual), "no history: the usual ones");
        for (int i = 0; i < 3; i++) lib.reacted("🔥");
        lib.reacted("🫠");
        lib.reacted("👍");
        lib.reacted("👍");
        assertEquals(java.util.List.of("🔥", "👍", "🫠", "❤️", "😂", "😮"), lib.mostReacted(6, usual),
                "most used first, ties newest first, then the usual ones not already there");
        assertEquals(java.util.List.of("🔥", "👍", "🫠", "❤️", "😂", "😮"), new MediaLibrary(f).mostReacted(6, usual), "kept in the file");
        assertEquals(java.util.List.of("🔥"), lib.mostReacted(1, usual));
    }
}
