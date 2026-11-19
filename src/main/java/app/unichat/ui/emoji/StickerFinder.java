package app.unichat.ui.emoji;

import app.unichat.model.MediaLibrary;
import app.unichat.model.StickerPack;
import app.unichat.support.I18n;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finding stickers as Telegram does: a word names emoji (Unicode's names and keywords in the language in use,
 * {@link EmojiSearch}), an emoji typed is itself, and a sticker is found by the emoji its set gave it (Telegram's bridge
 * says it, {@code fi.mau.bridged_sticker.emoji}). A set is also found by its name.
 */
final class StickerFinder {
    private StickerFinder() {}

    /** The emoji {@code query} asks for: those typed in it, and those its words name; none for a blank one. */
    static Set<String> wanted(String query, EmojiSearch names, List<String> all) {
        Set<String> out = new HashSet<>();
        String typed = plain(query);
        for (String e : all) if (typed.contains(plain(e))) out.add(plain(e));
        if (!EmojiSearch.split(query).isEmpty()) for (String e : names.find(query, all)) out.add(plain(e));
        return out;
    }

    /** The stickers of {@code sources} (in their order: favourites, recents, sets) with one of {@code wanted}, each once. */
    static List<MediaLibrary.Item> find(Set<String> wanted, List<List<MediaLibrary.Item>> sources) {
        Map<String, MediaLibrary.Item> out = new LinkedHashMap<>();
        for (List<MediaLibrary.Item> source : sources) {
            for (MediaLibrary.Item i : source) {
                String e = emoji(i);
                if (e != null && wanted.contains(e)) out.putIfAbsent(i.url(), i);
            }
        }
        return List.copyOf(out.values());
    }

    /** The sets whose name has every word of {@code query} (the start of one of its words). */
    static List<StickerPack> setsNamed(String query, List<StickerPack> packs) {
        List<String> typed = EmojiSearch.split(query);
        if (typed.isEmpty()) return List.of();
        return packs.stream().filter(p -> {
            List<String> own = EmojiSearch.split(p.title());
            return typed.stream().allMatch(t -> own.stream().anyMatch(w -> w.startsWith(t)));
        }).toList();
    }

    /** The emoji a sticker stands for, as its set gave it; null when it says none. */
    static String emoji(MediaLibrary.Item i) {
        if (i.info() == null) return null;
        String e = i.info().path("fi.mau.bridged_sticker").path("emoji").asText("");
        return e.isBlank() ? null : plain(e);
    }

    /** The text {@code key} says of a sticker, its emoji first ({@code {0}}), without the gap one without leaves. */
    static String says(String key, MediaLibrary.Item i, Object... more) {
        Object[] args = new Object[more.length + 1];
        String e = emoji(i);
        args[0] = e == null ? "" : e;
        System.arraycopy(more, 0, args, 1, more.length);
        return I18n.t(key, args).replace("  ", " ").strip();
    }

    /** Without the variation marks (U+FE0F/FE0E): "❤️" and "❤" are the same heart. */
    static String plain(String s) {
        return s.replace("\uFE0F", "").replace("\uFE0E", "").strip();
    }
}
