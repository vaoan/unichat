package app.unichat.gif;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The GIF providers Unichat knows and which ones the user turned on (Ajustes → GIFs: {@code gif.<id>.enabled} and
 * {@code gif.<id>.key}). Adding a provider = one adapter + one line here.
 */
public final class GifProviders {
    private GifProviders() {}

    /** A provider as shown in Ajustes: where to get a free key. */
    public record Known(String id, String name, String keyUrl, Function<String, GifProvider> create) {}

    private static final List<Known> KNOWN = List.of(
            new Known("giphy", "Giphy", "https://developers.giphy.com/dashboard/", GiphyProvider::new),
            new Known("klipy", "Klipy", "https://partner.klipy.com/api-keys", KlipyProvider::new));

    public static List<Known> known() {
        return KNOWN;
    }

    /** Providers turned on that have a key; {@code setting} reads a settings value (null when unset). */
    public static List<GifProvider> active(Function<String, String> setting) {
        List<GifProvider> out = new ArrayList<>();
        for (Known k : KNOWN) {
            String key = setting.apply("gif." + k.id() + ".key");
            if ("true".equals(setting.apply("gif." + k.id() + ".enabled")) && key != null && !key.isBlank()) {
                out.add(k.create().apply(key.strip()));
            }
        }
        return out;
    }
}
