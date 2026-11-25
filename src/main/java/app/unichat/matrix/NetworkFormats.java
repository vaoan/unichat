package app.unichat.matrix;

import app.unichat.model.Network;
import app.unichat.model.RoomFeatures;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * What each network gets for stickers and GIFs, in one place: which formats its bridge sends on as they are, what an
 * animated or a still one in another format becomes, and whether a GIF that came as an MP4 goes as it is. A new network,
 * or a change of mind about one, is a line here, not a check in each sender. Pure; {@link StickerConverter} converts.
 */
final class NetworkFormats {
    private NetworkFormats() {}

    static final String WEBP = "image/webp";
    static final String PNG = "image/png";
    static final String JPEG = "image/jpeg";
    static final String GIF = "image/gif";
    static final String WEBM = "video/webm";
    static final String LOTTIE = "video/lottie+json";

    /**
     * Whether a sticker that couldn't be made what {@code n} should get (no tool) goes as it is rather than as a still
     * picture: an animated one where that format still plays; never by room_features alone (null network).
     */
    static boolean asIsIfUnmade(@Nullable Network n, @Nullable String mime, boolean animated) {
        return n != null && animated && of(n).takesIfUnmade(mime);
    }

    /**
     * {@code asIs}: the sticker formats its bridge sends on (the chat's room_features may still refuse one); {@code animated}
     * and {@code still}: what one in another format becomes; {@code gifVideosAsIs}: its bridge sends a GIF that came as an
     * MP4 (marked {@code fi.mau.gif}) on as a GIF, and takes one only as an MP4 ({@code MessageSender.gifAsMp4}); {@code ifUnmade}: what it still shows as it is when the conversion
     * can't be made.
     */
    record Rule(Set<String> asIs, String animated, String still, boolean gifVideosAsIs, Set<String> ifUnmade) {
        /** Whether a {@code mime} sticker still goes as it is when it can't be made what it should be (no tool). */
        boolean takesIfUnmade(@Nullable String mime) {
            return mime != null && ifUnmade.contains(mime);
        }
    }

    /**
     * Telegram's bridge sends PNG and JPEG (it encodes them itself), WebM and Lottie on, and everything else, WebP
     * included, through an ffmpeg that may have no WebP encoder; an animated GIF becomes a video sticker. WhatsApp's
     * stickers are WebP (animated ones too) or PNG. Elsewhere (Discord…) a WebM is a video to press play on,
     * while a GIF and an animated WebP play by themselves; the WebP is lighter and keeps a smooth transparency.
     */
    static Rule of(Network n) {
        return switch (n) {
            case TELEGRAM -> new Rule(Set.of(PNG, JPEG, WEBM, LOTTIE), WEBM, PNG, true, Set.of());
            case WHATSAPP -> new Rule(Set.of(WEBP, PNG), WEBP, PNG, true, Set.of());
            // A GIF goes as the lighter animated WebP, as it is when gif2webp can't make one: it plays there either way.
            // Lottie (nobody else reads it) becomes the animated WebP too (StickerConverter, video/LottieStickers).
            default -> new Rule(Set.of(WEBP, PNG, JPEG), WEBP, PNG, false, Set.of(GIF));
        };
    }

    /**
     * The format a {@code mime} sticker goes to {@code network} as, null for as it is. {@code animated}: whether it moves
     * (null when the file isn't read yet: then "might it change" is answered). {@code own}: one of that network's own
     * packs, which its bridge sends from the pack (only the chat's room_features can refuse it). A {@code mime} that's
     * null (a sticker whose event didn't say) is none its bridge sends on.
     */
    static @Nullable String sticker(Network network, @Nullable String mime, @Nullable Boolean animated, boolean own, RoomFeatures f) {
        Rule r = of(network);
        String want = mime;
        if (!own && (mime == null || !r.asIs().contains(mime))) {
            if (animated == null) return r.animated(); // to be decided with the file: it changes either way
            want = animated ? r.animated() : r.still();
        }
        String refused = byFeatures(want, f);
        if (refused != null) want = refused;
        return want == null || want.equals(mime) ? null : want; // null: its own, of a type unknown, as it is
    }

    /** What the chat's room_features ask for instead of {@code mime}, null when they take it (or say nothing). */
    static @Nullable String byFeatures(@Nullable String mime, RoomFeatures f) {
        if (f.isUnknown() || !f.accepts(RoomFeatures.STICKER) || f.acceptsMime(RoomFeatures.STICKER, mime)) return null;
        if (f.acceptsMime(RoomFeatures.STICKER, WEBP)) return WEBP;
        if (f.acceptsMime(RoomFeatures.STICKER, PNG)) return PNG;
        return null;
    }
}
