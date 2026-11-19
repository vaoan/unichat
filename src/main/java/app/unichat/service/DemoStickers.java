package app.unichat.service;

import app.unichat.model.MediaLibrary;
import app.unichat.model.StickerPack;
import app.unichat.support.I18n;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
        // Each with its emoji, as Telegram's bridge gives a set's stickers: what searching stickers finds them by.
        String[][] named = {{"hola", "👋"}, {"jaja", "😂"}, {"gracias", "🙏"}, {"ok", "👌"}, {"wow", "😮"}, {"chao", "👋"}};
        for (String[] n : named) {
            ObjectNode info = JsonNodeFactory.instance.objectNode();
            info.putObject("fi.mau.bridged_sticker").put("emoji", n[1]);
            stickers.add(new MediaLibrary.Item(MediaLibrary.Kind.STICKER, "mxc://demo/sticker-" + n[0], "image/png", 640, 420, info));
        }
        return CompletableFuture.completedFuture(new StickerPack(I18n.t("demo.stickerSet"), url, stickers));
    }
}
