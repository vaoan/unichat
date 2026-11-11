package app.unichat.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A sticker set of a network (Telegram's, through its bridge): its name, its link and its stickers, each a library
 * item that keeps the bridge's mark so the network gets its own sticker back.
 */
public record StickerPack(String title, String url, List<MediaLibrary.Item> stickers) {
    /** A set before its stickers are fetched. */
    public record Ref(String title, String url) {}

    /** Telegram's set links; a set's short name is letters, digits and underscores. */
    private static final Pattern LINK = Pattern.compile("https://t\\.me/addstickers/[A-Za-z0-9_]{1,64}");

    public StickerPack {
        stickers = List.copyOf(stickers);
    }

    public static boolean isLink(String url) {
        return url != null && LINK.matcher(url).matches();
    }

    /** The set a sticker comes from ({@code fi.mau.bridged_sticker} in its info), or null. */
    public static String urlOf(JsonNode info) {
        String url = info == null ? null : info.path("fi.mau.bridged_sticker").path("pack_url").asText(null);
        return isLink(url) ? url : null;
    }
}
