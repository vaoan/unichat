package app.unichat.service;

import app.unichat.model.Network;
import app.unichat.settings.Settings;

import java.util.Locale;

/** How a network gets animated (GIF) stickers: Ajustes → Redes. */
public enum StickerMode {
    STICKER, GIF, VIDEO;

    public static String key(Network n) {
        return n.name().toLowerCase(Locale.ROOT) + ".animatedStickers";
    }

    public static StickerMode of(Network n) {
        String v = Settings.get().getString(key(n), STICKER.name());
        try {
            return valueOf(v);
        } catch (IllegalArgumentException e) {
            return STICKER;
        }
    }
}
