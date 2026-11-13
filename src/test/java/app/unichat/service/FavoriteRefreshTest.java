package app.unichat.service;

import app.unichat.model.MediaLibrary;
import app.unichat.model.StickerPack;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Favourites kept what the sticker was when saved: Telegram's video stickers as the GIF its bridge made of them then
 * (before it kept WebM), no size. Rebuilt, each is its set's sticker as the bridge gives it now, in the same place.
 */
class FavoriteRefreshTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SET = "https://t.me/addstickers/FluxAnimation";
    @TempDir Path dir;
    private final List<String> asked = new ArrayList<>();

    private static JsonNode info(String id, String mime) throws Exception {
        return JSON.readTree("{\"mimetype\":\"" + mime + "\",\"fi.mau.bridged_sticker\":{\"network\":\"telegram\",\"id\":\"" + id
                + "\",\"pack_url\":\"" + SET + "\"}}");
    }

    private static MediaLibrary.Item tg(String url, String id, String mime, int w) throws Exception {
        return new MediaLibrary.Item(MediaLibrary.Kind.STICKER, url, mime, w, w, info(id, mime));
    }

    private StickerSets sets(Map<String, CompletableFuture<StickerPack>> packs) {
        return new StickerSets() {
            @Override public CompletableFuture<List<StickerPack.Ref>> stickerPacks() { return CompletableFuture.completedFuture(List.of()); }
            @Override public CompletableFuture<StickerPack> stickerPack(String url) { throw new AssertionError("a rebuild asks afresh"); }
            @Override public CompletableFuture<StickerPack> freshStickerPack(String url) {
                asked.add(url);
                return packs.getOrDefault(url, CompletableFuture.failedFuture(new IllegalStateException("no such set")));
            }
        };
    }

    @Test
    void eachTelegramFavouriteBecomesItsSetsStickerNowInTheSamePlace() throws Exception {
        MediaLibrary lib = new MediaLibrary(dir.resolve("lib.json"));
        MediaLibrary.Item other = new MediaLibrary.Item(MediaLibrary.Kind.STICKER, "mxc://media-discord.localhost/x", "image/gif", 160, 160, null);
        lib.toggleFavorite(tg("mxc://media-telegram.localhost/gone", "9", "image/gif", 256)); // no longer in its set
        lib.toggleFavorite(other);
        lib.toggleFavorite(tg("mxc://media-telegram.localhost/old", "5", "image/gif", 256));
        lib.toggleFavorite(MediaLibrary.emoji("😅"));
        StickerPack now = new StickerPack("Flux", SET, List.of(
                tg("mxc://media-telegram.localhost/new", "5", "video/webm", 0),
                tg("mxc://media-telegram.localhost/another", "6", "video/webm", 0)));

        FavoriteRefresh.Result r = FavoriteRefresh.run(sets(Map.of(SET, CompletableFuture.completedFuture(now))), lib).get();

        List<MediaLibrary.Item> fav = lib.favorites(MediaLibrary.Kind.STICKER);
        assertEquals(List.of("mxc://media-telegram.localhost/new", "mxc://media-discord.localhost/x", "mxc://media-telegram.localhost/gone"),
                fav.stream().map(MediaLibrary.Item::url).toList());
        assertEquals("video/webm", fav.get(0).mime());
        assertEquals(256, fav.get(0).width(), "the set gives no size: the one known stays");
        assertEquals(List.of(SET), asked, "one set asked once");
        assertEquals(new FavoriteRefresh.Result(1, 1, 0), r, "1 rebuilt, 1 no longer in its set, no set that failed");
    }

    @Test
    void aSetThatCantBeBroughtLeavesItsFavouritesAsTheyWere() throws Exception {
        MediaLibrary lib = new MediaLibrary(dir.resolve("lib.json"));
        MediaLibrary.Item old = tg("mxc://media-telegram.localhost/old", "5", "image/gif", 256);
        lib.toggleFavorite(old);

        FavoriteRefresh.Result r = FavoriteRefresh.run(sets(Map.of()), lib).get();

        assertEquals(List.of(old), lib.favorites(MediaLibrary.Kind.STICKER));
        assertEquals(new FavoriteRefresh.Result(0, 0, 1), r);
    }
}
