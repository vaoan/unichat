package app.unichat.service;

import app.unichat.model.MediaLibrary;
import app.unichat.model.StickerPack;
import app.unichat.support.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** The demo's sticker set: its own pictures; any other set can't be fetched, as without a Telegram account. */
final class DemoStickers {
    static final String SET = "https://t.me/addstickers/unichat_demo";

    private DemoStickers() {}

    static CompletableFuture<List<StickerPack.Ref>> packs() {
        return CompletableFuture.completedFuture(List.of(new StickerPack.Ref(I18n.t("demo.stickerSet"), SET)));
    }

    static CompletableFuture<StickerPack> pack(String url) {
        if (!SET.equals(url)) return CompletableFuture.failedFuture(new UnsupportedOperationException("sticker sets"));
        List<MediaLibrary.Item> stickers = new ArrayList<>();
        for (String name : List.of("hola", "jaja", "gracias", "ok", "wow", "chao")) {
            stickers.add(new MediaLibrary.Item(MediaLibrary.Kind.STICKER, "mxc://demo/sticker-" + name, "image/png", 640, 420, null));
        }
        return CompletableFuture.completedFuture(new StickerPack(I18n.t("demo.stickerSet"), url, stickers));
    }
}
