package app.unichat.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finding an emoji by what it's called, in the language in use: Unicode's names and keywords (CLDR, in
 * {@code emoji/names_<language>.txt}, made by native/emoji-list.py; English when a language has none). Each word typed
 * must begin a word of the emoji's name or keywords, ignoring accents and case: "corazon roj" finds ❤️. Pure.
 */
final class EmojiSearch {
    private final Map<String, String> words;

    EmojiSearch(Map<String, String> words) {
        this.words = words;
    }

    /** For {@code language} (an ISO code), from the app's own files. */
    static EmojiSearch forLanguage(String language) {
        Map<String, String> w = load(language);
        return new EmojiSearch(w.isEmpty() ? load("en") : w);
    }

    /** The emoji whose name the query fits, in the list's order; all of them for a blank query. */
    List<String> find(String query, List<String> among) {
        List<String> typed = split(query);
        if (typed.isEmpty()) return among;
        List<String> out = new ArrayList<>();
        for (String e : among) {
            List<String> own = split(words.getOrDefault(e, ""));
            if (typed.stream().allMatch(t -> own.stream().anyMatch(w -> w.startsWith(t)))) out.add(e);
        }
        return out;
    }

    /** The emoji's name, for a screen reader or a tooltip; null when there's none. */
    String name(String emoji) {
        String w = words.get(emoji);
        return w == null ? null : w.split("\\|", 2)[0];
    }

    /** Lower case, without accents, cut at anything that isn't a letter or digit. */
    static List<String> split(String s) {
        String plain = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        List<String> out = new ArrayList<>();
        for (String w : plain.split("[^\\p{L}\\p{N}+]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    private static Map<String, String> load(String language) {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = EmojiSearch.class.getResourceAsStream("/app/unichat/emoji/names_" + language + ".txt")) {
            if (in == null) return out;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                int tab = line.indexOf('\t');
                if (tab > 0) out.put(line.substring(0, tab), line.substring(tab + 1));
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }
}
