package app.unichat.matrix;

import app.unichat.model.Network;
import app.unichat.model.RoomFeatures;

import java.util.Set;

/**
 * What each network gets for stickers and GIFs, in one place: which formats its bridge sends on as they are, what an
 * animated or a still one in another format becomes, and whether a GIF that came as an MP4 goes as it is. A new network,
 * or a change of mind about one, is a line here, not a check in each sender. Pure; {@link StickerConverter} converts.
 */
final class NetworkFormats {
    private NetworkFormats() {}

    static final String WEBP = "image/webp", PNG = "image/png", JPEG = "image/jpeg", GIF = "image/gif", WEBM = "video/webm",
            LOTTIE = "video/lottie+json";

    /**
     * {@code asIs}: the sticker formats its bridge sends on (the chat's room_features may still refuse one); {@code animated}
     * and {@code still}: what one in another format becomes; {@code gifVideosAsIs}: its bridge sends a GIF that came as an
     * MP4 (marked {@code fi.mau.gif}) on as a GIF.
     */
    record Rule(Set<String> asIs, String animated, String still, boolean gifVideosAsIs) {}

    /**
     * Telegram's bridge sends PNG and JPEG (it encodes them itself), WebM and Lottie on, and everything else, WebP
     * included, through an ffmpeg that may have no WebP encoder; an animated GIF becomes a video sticker. WhatsApp's
     * stickers are WebP (animated ones too) or PNG. Elsewhere (Discord…) a WebM is a video to press play on,
     * while a GIF and an animated WebP play by themselves; the WebP is lighter and keeps a smooth transparency.
     */
    static Rule of(Network n) {
        return switch (n) {
            case TELEGRAM -> new Rule(Set.of(PNG, JPEG, WEBM, LOTTIE), WEBM, PNG, true);
            case WHATSAPP -> new Rule(Set.of(WEBP, PNG), WEBP, PNG, true);
            default -> new Rule(Set.of(GIF, WEBP, PNG, JPEG), WEBP, PNG, false);
        };
    }

    /**
     * The format a {@code mime} sticker goes to {@code network} as, null for as it is. {@code animated}: whether it moves
     * (null when the file isn't read yet: then "might it change" is answered). {@code own}: one of that network's own
     * packs, which its bridge sends from the pack (only the chat's room_features can refuse it).
     */
    static String sticker(Network network, String mime, Boolean animated, boolean own, RoomFeatures f) {
        Rule r = of(network);
        String want = mime;
        if (!own && !r.asIs().contains(mime)) {
            if (animated == null) return r.animated(); // to be decided with the file: it changes either way
            want = animated ? r.animated() : r.still();
        }
        String refused = byFeatures(want, f);
        if (refused != null) want = refused;
        return want.equals(mime) ? null : want;
    }

    /** What the chat's room_features ask for instead of {@code mime}, null when they take it (or say nothing). */
    static String byFeatures(String mime, RoomFeatures f) {
        if (f.isUnknown() || !f.accepts(RoomFeatures.STICKER) || f.acceptsMime(RoomFeatures.STICKER, mime)) return null;
        if (f.acceptsMime(RoomFeatures.STICKER, WEBP)) return WEBP;
        if (f.acceptsMime(RoomFeatures.STICKER, PNG)) return PNG;
        return null;
    }
}
