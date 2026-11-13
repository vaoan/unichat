package app.unichat.service;

import app.unichat.model.MediaLibrary;
import app.unichat.model.StickerPack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Rebuilds the favourite stickers from their sets: a favourite keeps what the sticker was when it was saved (a Telegram
 * video sticker as the GIF its bridge made of it then, no size), so each with a set and the bridge's id becomes that
 * set's sticker as the bridge gives it now, where it stood. One not in its set any more, or whose set can't be brought,
 * stays as it was. Sets are asked one after another: the bridge fetches each from Telegram sticker by sticker.
 */
public final class FavoriteRefresh {
    /** How many were rebuilt, how many their set no longer has, and how many sets couldn't be brought. */
    public record Result(int rebuilt, int missing, int failedSets) {}

    private FavoriteRefresh() {}

    public static CompletableFuture<Result> run(StickerSets sets, MediaLibrary library) {
        Map<String, List<MediaLibrary.Item>> bySet = new LinkedHashMap<>();
        for (MediaLibrary.Item i : library.favorites(MediaLibrary.Kind.STICKER)) {
            String set = StickerPack.urlOf(i.info());
            if (set != null && MediaLibrary.bridgeId(i) != null) bySet.computeIfAbsent(set, k -> new ArrayList<>()).add(i);
        }
        List<MediaLibrary.Replacement> replacements = new ArrayList<>();
        int[] missing = {0}, failed = {0};
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Map.Entry<String, List<MediaLibrary.Item>> e : bySet.entrySet()) {
            chain = chain.thenCompose(v -> sets.freshStickerPack(e.getKey()).handle((pack, err) -> {
                if (pack == null) {
                    failed[0]++;
                    System.err.println("Favourites: a sticker set couldn't be brought (" + (err == null ? "none" : err.getClass().getSimpleName()) + ")");
                    return null;
                }
                for (MediaLibrary.Item old : e.getValue()) {
                    MediaLibrary.Item now = inSet(pack, MediaLibrary.bridgeId(old));
                    if (now == null) missing[0]++;
                    else replacements.add(new MediaLibrary.Replacement(old, rebuilt(old, now)));
                }
                return null;
            }));
        }
        return chain.thenApply(v -> {
            library.replace(replacements);
            return new Result(replacements.size(), missing[0], failed[0]);
        });
    }

    private static MediaLibrary.Item inSet(StickerPack pack, String id) {
        return pack.stickers().stream().filter(s -> id.equals(MediaLibrary.bridgeId(s))).findFirst().orElse(null);
    }

    /** The set's sticker; a size the set doesn't give is the one the favourite knew. */
    static MediaLibrary.Item rebuilt(MediaLibrary.Item old, MediaLibrary.Item now) {
        return new MediaLibrary.Item(old.kind(), now.url(), now.mime(), now.width() > 0 ? now.width() : old.width(),
                now.height() > 0 ? now.height() : old.height(), now.info());
    }
}
