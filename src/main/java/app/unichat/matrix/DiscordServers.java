package app.unichat.matrix;

import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Network;
import app.unichat.service.BridgeAccess.DiscordGuild;
import app.unichat.service.ChatService.GroupOffer;
import app.unichat.settings.Settings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import static app.unichat.matrix.BridgeProtocol.*;
import static app.unichat.matrix.MatrixLogin.userMessage;

/** Discord servers: which are bridged, offering the rest, and archived servers bringing nothing. */
final class DiscordServers {
    private final MatrixCore core;
    private final SyncProcessor proc;
    private final BotChat bots;
    private final Supplier<List<Account>> accounts;

    DiscordServers(MatrixCore core, BotChat bots, Supplier<List<Account>> accounts) {
        this.core = core;
        this.proc = core.proc;
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

    /**
     * Each room inside a Discord server (a channel, a category, a channel under a category, the server's own space) →
     * that server's space, however deep, through the rooms' parents and the spaces' children. A DM, a group DM and the
     * bridge's own folder are in none. One pass for all rooms: asked on every sync. Caller holds the processor's lock.
     */
    Map<String, String> serverSpaces() {
        Map<String, Set<String>> parents = new HashMap<>();
        for (MatrixRoom r : proc.rooms()) {
            parents.computeIfAbsent(r.id, k -> new java.util.HashSet<>()).addAll(r.chat.spaceParents());
            if (r.space) for (String c : r.chat.spaceChildren()) parents.computeIfAbsent(c, k -> new java.util.HashSet<>()).add(r.id);
        }
        Map<String, String> out = new HashMap<>();
        for (MatrixRoom r : proc.rooms()) {
            String s = serverSpace(r.id, parents, new java.util.HashSet<>());
            if (s != null) out.put(r.id, s);
        }
        return out;
    }

    private String serverSpace(String id, Map<String, Set<String>> parents, Set<String> seen) {
        if (!seen.add(id)) return null;
        if (guildOf(id) != null) return id;
        for (String p : parents.getOrDefault(id, Set.of())) {
            String s = serverSpace(p, parents, seen);
            if (s != null) return s;
        }
        return null;
    }

    static Set<String> pausedGuilds() {
        return Settings.get().getSet(PAUSED_GUILDS_KEY);
    }

    /** A pause (or resume) the bridge didn't take, waiting to be tried again. */
    private record PauseRetry(boolean paused, Retry retry) {}

    /** By guild id. */
    private final Map<String, PauseRetry> pauseRetries = new HashMap<>();

    /** Tries again the pauses and resumes that failed and whose time has come, as {@link ChatActions#applyMutes} does mute rules. */
    CompletableFuture<Void> retryGuildPauses(Instant now) {
        List<CompletableFuture<Void>> runs = new ArrayList<>();
        synchronized (pauseRetries) {
            for (var it = pauseRetries.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (!e.getValue().retry().due(now)) continue;
                it.remove();
                String guild = e.getKey();
                PauseRetry r = e.getValue();
                runs.add(CompletableFuture.runAsync(() -> setGuildPausedNow(guild, r.paused(), r.retry()), core.io));
            }
        }
        return CompletableFuture.allOf(runs.toArray(new CompletableFuture[0]));
    }

    /** Never throws: a failure is logged and tried again later; {@code failedBefore} is null for a first try. */
    private void setGuildPausedNow(String guildId, boolean paused, Retry failedBefore) {
        try {
            String reply = bots.query(Network.DISCORD, "guilds bridging-mode " + guildId + " " + (paused ? "nothing" : "create-on-message"));
            if (!reply.startsWith("Set guild bridging mode")) throw new MatrixException(0, null, reply);
            if (paused) Settings.get().addToSet(PAUSED_GUILDS_KEY, guildId);
            else Settings.get().removeFromSet(PAUSED_GUILDS_KEY, guildId);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("Discord server " + (paused ? "pause" : "resume") + " failed, will retry: " + e);
            synchronized (pauseRetries) {
                // A newer choice made meanwhile stays the one to retry.
                pauseRetries.putIfAbsent(guildId, new PauseRetry(paused, Retry.after(failedBefore, Instant.now())));
            }
        }
    }

    /** What follows Discord being connected: servers set as the archive says ({@link ArchivedServers}). */
    private volatile Runnable whenConnected = () -> { };

    void onConnected(Runnable r) { whenConnected = r; }

    /** With Discord connected, servers archived earlier (or elsewhere) stop bringing anything too. */
    void maybePauseArchivedGuilds(Network n, List<Login> known) {
        if (n != Network.DISCORD || known.stream().noneMatch(l -> "CONNECTED".equals(l.state()))) return;
        if (core.initialSyncDone) whenConnected.run();
    }

    private boolean connected() {
        return accounts.get().stream().anyMatch(a -> a.network() == Network.DISCORD && a.status() == AccountStatus.CONNECTED);
    }

    /**
     * Of the servers in {@code paused} (guild id → whether it should bring nothing), those to be set to {@code to} at the
     * bridge: not already so, nor waiting to be tried again so. None while Discord isn't connected: its bot can't answer.
     */
    List<String> toSet(Map<String, Boolean> paused, boolean to) {
        if (!connected()) return List.of();
        Set<String> done = pausedGuilds();
        List<String> out = new ArrayList<>();
        synchronized (pauseRetries) {
            for (var e : paused.entrySet()) {
                if (e.getValue() != to || done.contains(e.getKey()) == to) continue;
                PauseRetry r = pauseRetries.get(e.getKey());
                if (r == null || r.paused() != to) out.add(e.getKey());
            }
        }
        return out;
    }

    /**
     * An archived server brings nothing (no messages, no attachments filling the disk) while its rooms rest; woken or
     * brought back, it brings messages again, creating channels as people write. On the caller's thread, which waits
     * for the bot's answer; never throws (a refusal is tried again).
     */
    void setGuildPaused(String guildId, boolean paused) {
        synchronized (pauseRetries) {
            pauseRetries.remove(guildId); // the latest choice is the one that's tried again
        }
        setGuildPausedNow(guildId, paused, null);
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
        if (guild == null || Settings.get().getSet(ENTIRE_GUILDS_KEY).contains(guild)) return CompletableFuture.completedFuture(null);
        return guildCommand("guilds bridge " + guild + " --entire", 600, () -> Settings.get().addToSet(ENTIRE_GUILDS_KEY, guild));
    }

    private static final String ENTIRE_GUILDS_KEY = "discord.guilds.entire";

    CompletableFuture<List<DiscordGuild>> discordGuilds() {
        return core.call(() -> parseGuilds(bots.query(Network.DISCORD, "guilds status")));
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
        return guildCommand(command, seconds, () -> guildCache = null);
    }

    /** A guilds command that answers "Successfully…", then {@code after}; any other answer is the failure's message. */
    private CompletableFuture<Void> guildCommand(String command, int seconds, Runnable after) {
        return CompletableFuture.runAsync(() -> {
            try {
                requireSuccess(bots.query(Network.DISCORD, command, java.time.Duration.ofSeconds(seconds)));
                after.run();
            } catch (MatrixException e) {
                throw new CompletionException(e);
            } catch (Exception e) {
                throw new CompletionException(new MatrixException(userMessage(e), e));
            }
        }, core.io);
    }
}
