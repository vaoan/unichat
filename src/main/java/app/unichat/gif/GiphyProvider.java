package app.unichat.gif;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static app.unichat.support.Urls.enc;
import static app.unichat.gif.HttpJson.text;

/** Giphy (developers.giphy.com): {@code /v1/gifs/search} and {@code /v1/gifs/trending}, rating pg-13. */
public final class GiphyProvider implements GifProvider {
    public static final String BASE = "https://api.giphy.com";
    private final String key;
    private final String base;

    public GiphyProvider(String key) {
        this(key, BASE);
    }

    GiphyProvider(String key, String base) {
        this.key = key;
        this.base = base;
    }

    @Override public String id() { return "giphy"; }
    @Override public String name() { return "Giphy"; }

    @Override
    public List<Gif> search(String query, int limit) throws IOException, InterruptedException {
        return parse(HttpJson.get(base + "/v1/gifs/search?api_key=" + enc(key) + "&q=" + enc(query) + "&limit=" + limit + "&rating=pg-13"));
    }

    @Override
    public List<Gif> trending(int limit) throws IOException, InterruptedException {
        return parse(HttpJson.get(base + "/v1/gifs/trending?api_key=" + enc(key) + "&limit=" + limit + "&rating=pg-13"));
    }

    static List<Gif> parse(JsonNode root) {
        List<Gif> out = new ArrayList<>();
        for (JsonNode d : root.path("data")) {
            JsonNode small = d.path("images").path("fixed_width"), orig = d.path("images").path("original");
            String preview = text(small.path("url")), gif = text(orig.path("url"));
            if (preview == null || gif == null) continue;
            out.add(new Gif("giphy", d.path("id").asText(), d.path("title").asText(""), preview, text(orig.path("mp4")), gif,
                    orig.path("width").asInt(0), orig.path("height").asInt(0)));
        }
        return out;
    }
}
