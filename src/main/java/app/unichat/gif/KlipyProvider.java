package app.unichat.gif;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static app.unichat.support.Urls.enc;
import static app.unichat.gif.HttpJson.text;

/**
 * Klipy (docs.klipy.com): {@code /api/v1/{app_key}/gifs/search|trending}; items carry {@code file.{hd,md,sm,xs}.{gif,mp4}}.
 * Its optional customer_id is never sent (only the search text leaves Unichat); ad items are skipped.
 */
public final class KlipyProvider implements GifProvider {
    public static final String BASE = "https://api.klipy.com";
    private final String key;
    private final String base;

    public KlipyProvider(String key) {
        this(key, BASE);
    }

    KlipyProvider(String key, String base) {
        this.key = key;
        this.base = base;
    }

    @Override public String id() { return "klipy"; }
    @Override public String name() { return "Klipy"; }

    @Override
    public List<Gif> search(String query, int limit) throws IOException, InterruptedException {
        return parse(HttpJson.get(base + "/api/v1/" + enc(key) + "/gifs/search?q=" + enc(query) + "&per_page=" + Math.min(50, limit)));
    }

    @Override
    public List<Gif> trending(int limit) throws IOException, InterruptedException {
        return parse(HttpJson.get(base + "/api/v1/" + enc(key) + "/gifs/trending?per_page=" + Math.min(50, limit)));
    }

    static List<Gif> parse(JsonNode root) {
        List<Gif> out = new ArrayList<>();
        for (JsonNode d : root.path("data").path("data")) {
            JsonNode file = d.path("file");
            if (file.isMissingNode() || "ad".equals(d.path("type").asText())) continue;
            JsonNode big = first(file, "hd", "md", "sm", "xs"), small = first(file, "sm", "xs", "md", "hd");
            String preview = text(small.path("gif").path("url")), gif = text(big.path("gif").path("url"));
            if (preview == null || gif == null) continue;
            out.add(new Gif("klipy", d.path("id").asText(), d.path("title").asText(""), preview, text(big.path("mp4").path("url")), gif,
                    big.path("gif").path("width").asInt(0), big.path("gif").path("height").asInt(0)));
        }
        return out;
    }

    private static JsonNode first(JsonNode file, String... sizes) {
        for (String s : sizes) if (file.has(s)) return file.get(s);
        return file.path(sizes[0]);
    }
}
