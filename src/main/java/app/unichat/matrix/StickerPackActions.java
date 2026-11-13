package app.unichat.matrix;

import app.unichat.login.BridgeStickerPacks;
import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Network;
import app.unichat.model.StickerPack;
import app.unichat.settings.Settings;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static app.unichat.matrix.BridgeProtocol.loginIdOf;

/** Telegram's sticker sets through its bridge's own API, each set kept on disk ({@link StickerPackCache}). */
final class StickerPackActions {
    private final MatrixCore core;
    private final Supplier<List<Account>> accounts;
    private final StickerPackCache cache = new StickerPackCache(Settings.get().cacheDir().resolve("sticker-packs"));
    private final Map<String, StickerPack> loaded = new ConcurrentHashMap<>();

    StickerPackActions(MatrixCore core, Supplier<List<Account>> accounts) {
        this.core = core;
        this.accounts = accounts;
    }

    CompletableFuture<List<StickerPack.Ref>> packs() {
        return core.call(() -> {
            List<StickerPack.Ref> out = new ArrayList<>();
            for (BridgeStickerPacks b : bridges()) {
                for (StickerPack.Ref r : b.list().orElse(List.of())) if (!out.contains(r)) out.add(r);
            }
            return out;
        });
    }

    CompletableFuture<StickerPack> pack(String url) {
        return pack(url, false);
    }

    /** {@code url}'s set; {@code again}: from the bridge even when a copy is kept (it changes what the stickers are). */
    CompletableFuture<StickerPack> pack(String url, boolean again) {
        if (!StickerPack.isLink(url)) return CompletableFuture.failedFuture(new IllegalArgumentException("not a sticker set"));
        StickerPack known = again ? null : loaded.get(url);
        if (known != null) return CompletableFuture.completedFuture(known);
        return core.call(() -> {
            JsonNode content = again ? null : cache.fresh(url);
            Exception failed = null;
            for (BridgeStickerPacks b : content == null ? bridges() : List.<BridgeStickerPacks>of()) {
                try {
                    content = b.raw(url);
                } catch (Exception e) {
                    failed = e;
                }
                if (content != null) {
                    cache.put(url, content);
                    break;
                }
            }
            if (content == null) content = cache.any(url); // the bridge can't now: last week's copy will do
            if (content == null) throw failed != null ? failed : new IllegalStateException("no connected Telegram account");
            StickerPack p = BridgeStickerPacks.pack(content, url);
            loaded.put(url, p);
            return p;
        });
    }

    /** One per connected Telegram account; none on a server whose bridges Unichat doesn't run. */
    private List<BridgeStickerPacks> bridges() {
        BridgeProvisioner p = BridgeAccounts.provisioner;
        String base = p == null ? null : p.provisioningUrl(Network.TELEGRAM);
        if (base == null) return List.of();
        return accounts.get().stream()
                .filter(a -> a.network() == Network.TELEGRAM && a.status() == AccountStatus.CONNECTED)
                .map(a -> new BridgeStickerPacks(base, core.accessToken, core.userId, loginIdOf(a))).toList();
    }
}
