package app.unichat.login;

import app.unichat.model.MediaLibrary;
import app.unichat.model.Mxc;
import app.unichat.model.StickerPack;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static app.unichat.support.Urls.enc;

/**
 * A bridge's sticker sets (bridgev2 provisioning {@code /v3/image_pack/list|import}) for one login: Telegram's bridge
 * lists the sets the person has and gives a whole set by its link. Only ever previewed ({@code GET}): a {@code POST}
 * would import it into the bridge's space for good.
 */
public final class BridgeStickerPacks {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    /** A set is fetched from Telegram sticker by sticker: a big one takes a while. */
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final LoginApi api;

    /** @param base e.g. {@code http://127.0.0.1:29317/_matrix/provision} */
    public BridgeStickerPacks(String base, String accessToken, String userId, String loginId) {
        this.api = new LoginApi(base, accessToken, userId, loginId);
    }

    /** The person's sticker sets; empty when this bridge has none to give. */
    public Optional<List<StickerPack.Ref>> list() throws IOException, InterruptedException {
        JsonNode j = api.call(HTTP, "GET", "/v3/image_pack/list", "", null, TIMEOUT);
        return j == null ? Optional.empty() : Optional.of(refs(j));
    }

    /** The whole set, as the bridge gives it (see {@link #pack(JsonNode, String)}); null when this bridge can't. */
    public JsonNode raw(String url) throws IOException, InterruptedException {
        return api.call(HTTP, "GET", "/v3/image_pack/import", "&pack_url=" + enc(url), null, TIMEOUT);
    }

    public StickerPack pack(String url) throws IOException, InterruptedException {
        JsonNode j = raw(url);
        if (j == null) throw new BridgeLogin.BridgeLoginException("M_UNRECOGNIZED", "no sticker sets");
        return pack(j, url);
    }

    static List<StickerPack.Ref> refs(JsonNode list) {
        List<StickerPack.Ref> out = new ArrayList<>();
        for (JsonNode p : list) {
            boolean stickers = false;
            for (JsonNode u : p.path("usage")) stickers |= "sticker".equals(u.asText());
            String url = p.path("fi.mau.bridged_pack").path("url").asText(null);
            if (stickers && StickerPack.isLink(url)) out.add(new StickerPack.Ref(p.path("display_name").asText(url), url));
        }
        return out;
    }

    /** A set's content (an {@code im.ponies} image pack): its stickers in the bridge's order, only the server's addresses. */
    public static StickerPack pack(JsonNode content, String url) {
        List<MediaLibrary.Item> stickers = new ArrayList<>();
        for (JsonNode img : content.path("images")) {
            String mxc = img.path("url").asText("");
            if (Mxc.parse(mxc) == null) continue;
            JsonNode info = img.path("info");
            stickers.add(new MediaLibrary.Item(MediaLibrary.Kind.STICKER, mxc, info.path("mimetype").asText(null),
                    info.path("w").asInt(), info.path("h").asInt(), info.isObject() ? info : null));
        }
        return new StickerPack(content.path("pack").path("display_name").asText(url), url, stickers);
    }
}
