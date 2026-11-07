package app.unichat.matrix;

import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Network;
import app.unichat.service.BridgeAccess.DiscordGuild;
import app.unichat.service.ChatService.GroupOffer;
import app.unichat.settings.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import static app.unichat.matrix.BridgeProtocol.*;
import static app.unichat.matrix.MatrixClient.CLIENT;
import static app.unichat.support.Urls.enc;
import static app.unichat.matrix.MatrixLogin.userMessage;

/** Discord servers: which are bridged, offering the rest, and archived servers bringing nothing. */
final class DiscordServers {
    private final MatrixCore core;
    private final MatrixClient client;
    private final SyncProcessor proc;
    private final String userId;
    private final BotChat bots;
    private final Supplier<List<Account>> accounts;

    DiscordServers(MatrixCore core, BotChat bots, Supplier<List<Account>> accounts) {
        this.core = core;
        this.client = core.client;
        this.proc = core.proc;
        this.userId = core.userId;
        this.bots = bots;
        this.accounts = accounts;
    }

    /** Discord servers set to bring nothing (archived), so each is asked of the bridge once. */
    static final String PAUSED_GUILDS_KEY = "discord.guilds.paused";

    /** The Discord server id behind a server's space (not a category inside it, not the bridge's own folder), or null. */
    String guildOf(String chatId) {
        MatrixRoom r = proc.room(chatId);
        if (r == null || !r.space || r.chat.network() != Network.DISCORD || r.bridgeChannelId == null || r.chat.personalSpace()) {
            return null;
        }
        for (String p : r.chat.spaceParents()) {
            MatrixRoom pr = proc.room(p);
            if (pr != null && pr.space && pr.chat.network() == Network.DISCORD && !pr.chat.personalSpace()) return null;
        }
        return r.bridgeChannelId;
    }

    static Set<String> pausedGuilds() {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String id : Settings.get().getString(PAUSED_GUILDS_KEY, "").split(",")) if (!id.isBlank()) out.add(id.strip());
        return out;
    }

    /**
     * An archived server brings nothing (no messages, no attachments filling the disk) while its rooms stay in the
     * archive; brought back, it brings messages again, creating channels as people write.
     */
    void setGuildPaused(String guildId, boolean paused) {
        core.io.execute(() -> setGuildPausedNow(guildId, paused));
    }

    private void setGuildPausedNow(String guildId, boolean paused) {
        try {
            String reply = bots.query(Network.DISCORD, "guilds bridging-mode " + guildId + " " + (paused ? "nothing" : "create-on-message"));
            if (!reply.startsWith("Set guild bridging mode")) throw new MatrixException(0, null, reply);
            synchronized (PAUSED_GUILDS_KEY) {
                Set<String> now = pausedGuilds();
                if (paused) now.add(guildId); else now.remove(guildId);
                Settings.get().set(PAUSED_GUILDS_KEY, now.isEmpty() ? null : String.join(",", now));
            }
        } catch (Exception e) {
            System.err.println("Discord server " + (paused ? "pause" : "resume") + " failed: " + e.getClass().getSimpleName());
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean pausedArchivedGuilds = new java.util.concurrent.atomic.AtomicBoolean();

    /** Once per run, with Discord connected: servers archived earlier (or elsewhere) stop bringing anything too. */
    void maybePauseArchivedGuilds(Network n, List<Login> known) {
        if (n != Network.DISCORD || known.stream().noneMatch(l -> "CONNECTED".equals(l.state()))) return;
        if (!core.initialSyncDone || !pausedArchivedGuilds.compareAndSet(false, true)) return;
        Set<String> done = pausedGuilds();
        List<String> todo = new ArrayList<>();
        synchronized (proc) {
            for (MatrixRoom r : proc.rooms()) {
                String g = r.chat.archived() ? guildOf(r.id) : null;
                if (g != null && !done.contains(g)) todo.add(g);
            }
        }
        if (todo.isEmpty()) return;
        System.err.println("Discord: pausing " + todo.size() + " archived servers");
        // One after another in a single task: the bridge takes one command at a time anyway.
        core.io.execute(() -> {
            for (String g : todo) {
                if (!core.running) return;
                setGuildPausedNow(g, true);
            }
        });
    }

    /** The bridge's guild list, asked at most every few minutes (each ask is a message in the bot chat). */
    private volatile List<DiscordGuild> guildCache;

    private volatile long guildCacheAt;

    private static final long GUILD_CACHE_MS = 10 * 60_000;

    /** Discord guild ids whose space is already in Unichat. */
    private Set<String> localGuildIds() {
        Set<String> out = new java.util.HashSet<>();
        synchronized (proc) {
            for (MatrixRoom r : proc.rooms()) {
                if (r.space && r.chat.network() == Network.DISCORD && r.bridgeChannelId != null) out.add(r.bridgeChannelId);
            }
        }
        return out;
    }

    /** Every channel of a Discord server, asked once per server ({@code --entire}); other networks: nothing. */
    CompletableFuture<Void> loadAllChannels(String spaceId) {
        String guild;
        synchronized (proc) {
            MatrixRoom r = proc.room(spaceId);
            guild = r != null && r.space && r.chat.network() == Network.DISCORD ? r.bridgeChannelId : null;
        }
        Set<String> done = new java.util.HashSet<>(java.util.Arrays.asList(Settings.get().getString(ENTIRE_GUILDS_KEY, "").split(",")));
        if (guild == null || done.contains(guild)) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            try {
                String reply = bots.query(Network.DISCORD, "guilds bridge " + guild + " --entire", 600);
                if (!reply.startsWith("Successfully")) throw new MatrixException(0, null, reply);
                synchronized (ENTIRE_GUILDS_KEY) {
                    Set<String> now = new java.util.LinkedHashSet<>(java.util.Arrays.asList(Settings.get().getString(ENTIRE_GUILDS_KEY, "").split(",")));
                    now.remove("");
                    now.add(guild);
                    Settings.get().set(ENTIRE_GUILDS_KEY, String.join(",", now));
                }
            } catch (MatrixException e) {
                throw new CompletionException(e);
            } catch (Exception e) {
                throw new CompletionException(new MatrixException(userMessage(e), e));
            }
        }, core.io);
    }

    private static final String ENTIRE_GUILDS_KEY = "discord.guilds.entire";

    CompletableFuture<List<DiscordGuild>> discordGuilds() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return parseGuilds(bots.query(Network.DISCORD, "guilds status"));
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, core.io);
    }

    CompletableFuture<Void> setDiscordGuildBridged(String guildId, boolean bridged) {
        return changeBridging((bridged ? "guilds bridge " : "guilds unbridge ") + guildId, 20);
    }

    /** Discord servers you're in that aren't bridged yet: the Grupos view offers them. */
    CompletableFuture<List<GroupOffer>> groupOffers(Network network) {
        if (network != Network.DISCORD || accounts.get().stream().noneMatch(a -> a.network() == Network.DISCORD
                && a.status() == AccountStatus.CONNECTED)) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<DiscordGuild> cached = guildCache;
        if (cached != null && System.currentTimeMillis() - guildCacheAt < GUILD_CACHE_MS) {
            return CompletableFuture.completedFuture(offers(cached, localGuildIds()));
        }
        return discordGuilds().thenApply(gs -> {
            guildCache = gs;
            guildCacheAt = System.currentTimeMillis();
            return offers(gs, localGuildIds());
        });
    }

    /**
     * Bridges a whole Discord server ({@code --entire}: a room for every channel, so the Grupos tree shows them
     * all). Big servers take a while: the bridge answers once every channel room exists.
     */
    CompletableFuture<Void> addGroup(String offerId) {
        return changeBridging("guilds bridge " + offerId + " --entire", 600);
    }

    /** A guilds command that changes what's bridged, so the cached list no longer holds. */
    private CompletableFuture<Void> changeBridging(String command, int seconds) {
        return CompletableFuture.runAsync(() -> {
            try {
                String reply = bots.query(Network.DISCORD, command, seconds);
                if (!reply.startsWith("Successfully")) throw new MatrixException(0, null, reply);
                guildCache = null;
            } catch (MatrixException e) {
                throw new CompletionException(e);
            } catch (Exception e) {
                throw new CompletionException(new MatrixException(userMessage(e), e));
            }
        }, core.io);
    }
}
