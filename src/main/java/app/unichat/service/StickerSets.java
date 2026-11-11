package app.unichat.service;

import app.unichat.model.StickerPack;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Networks' sticker sets (Telegram's, through its bridge): the person's own, and any set by its link. */
public interface StickerSets {
    /** The person's sets on every connected network whose bridge lists them; empty where none does. */
    CompletableFuture<List<StickerPack.Ref>> stickerPacks();

    /** A whole set by its link; fails when no connected account can bring it. */
    CompletableFuture<StickerPack> stickerPack(String url);
}
