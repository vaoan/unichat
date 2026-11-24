package app.unichat.ui.emoji;

import app.unichat.model.MediaLibrary;
import app.unichat.model.StickerPack;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Finding stickers by a word (through the emoji it names, in the language in use) or by an emoji, as Telegram does. */
class StickerFinderTest {
    private static final EmojiSearch NAMES = new EmojiSearch(Map.of(
            "😂", "cara llorando de risa|risa|carcajada",
            "❤️", "corazón rojo|corazón|amor",
            "👽", "alienígena|extraterrestre",
            "🐱", "cara de gato|gato"));
    private static final List<String> ALL = List.of("😂", "❤️", "👽", "🐱");

    private static MediaLibrary.Item sticker(String url, @Nullable String emoji) {
        var info = new ObjectMapper().createObjectNode();
        if (emoji != null) info.putObject("fi.mau.bridged_sticker").put("emoji", emoji);
        return new MediaLibrary.Item(MediaLibrary.Kind.STICKER, url, "image/webp", 512, 512, info);
    }

    private static List<String> urls(List<MediaLibrary.Item> items) {
        return items.stream().map(MediaLibrary.Item::url).toList();
    }

    @Test
    void aWordFindsTheStickersOfTheEmojiItNames() {
        List<MediaLibrary.Item> favorites = List.of(sticker("mxc://s/a", "😂"), sticker("mxc://s/b", "🐱"));
        List<MediaLibrary.Item> set = List.of(sticker("mxc://s/c", "😂"), sticker("mxc://s/d", "❤️"));
        Set<String> wanted = StickerFinder.wanted("risa", NAMES, ALL);
        assertEquals(List.of("mxc://s/a", "mxc://s/c"), urls(StickerFinder.find(wanted, List.of(favorites, set))), "favourites first");
        assertEquals(List.of("mxc://s/b"), urls(StickerFinder.find(StickerFinder.wanted("GAT", NAMES, ALL), List.of(favorites, set))),
                "the start of a word, any case");
    }

    @Test
    void anEmojiTypedFindsItsOwnWhateverItsVariationMark() {
        List<MediaLibrary.Item> set = List.of(sticker("mxc://s/a", "👽\uFE0F"), sticker("mxc://s/b", "❤"));
        assertEquals(List.of("mxc://s/a"), urls(StickerFinder.find(StickerFinder.wanted("👽", NAMES, ALL), List.of(set))));
        assertEquals(List.of("mxc://s/b"), urls(StickerFinder.find(StickerFinder.wanted("❤️", NAMES, ALL), List.of(set))));
    }

    @Test
    void eachStickerOnceAndNoneWithoutAnEmoji() {
        MediaLibrary.Item a = sticker("mxc://s/a", "😂");
        assertEquals(List.of("mxc://s/a"), urls(StickerFinder.find(Set.of("😂"), List.of(List.of(a), List.of(a, sticker("mxc://s/x", null))))));
    }

    @Test
    void whatAStickerIsCalledWithOrWithoutItsEmoji() {
        assertEquals("Sticker 😂 encontrado", StickerFinder.says("library.stickerFound", sticker("mxc://s/a", "😂")));
        assertEquals("Sticker encontrado", StickerFinder.says("library.stickerFound", sticker("mxc://s/b", null)), "no gap where it isn't");
        assertEquals("Sticker de Gatos", StickerFinder.says("library.stickerInSet", sticker("mxc://s/b", null), "Gatos"));
    }

    @Test
    void nothingTypedFindsNothing() {
        assertTrue(StickerFinder.wanted("  ", NAMES, ALL).isEmpty());
    }

    @Test
    void aSetsNameFindsItsStickers() {
        StickerPack cats = new StickerPack("Gatos activados", "https://t.me/addstickers/cats", List.of(sticker("mxc://s/a", "😂")));
        StickerPack dogs = new StickerPack("Perritos", "https://t.me/addstickers/dogs", List.of());
        assertEquals(List.of(cats), StickerFinder.setsNamed("gatos act", List.of(cats, dogs)));
        assertEquals(List.of(), StickerFinder.setsNamed("😂", List.of(cats, dogs)), "an emoji names no set");
    }
}
