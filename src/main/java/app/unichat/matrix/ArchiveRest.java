package app.unichat.matrix;

import app.unichat.model.Network;
import app.unichat.service.ArchiveWaking.Step;
import app.unichat.service.ArchiveWaking.Waking;
import app.unichat.settings.Settings;
import app.unichat.support.Threads;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static app.unichat.matrix.MatrixClient.CLIENT;
import static app.unichat.support.Urls.enc;

/**
 * Archived Discord servers and channels rest out of the sync: they're most of the person's thousands of rooms, and
 * each sync made the server walk them all. {@link SyncFilter} leaves them out and their servers bring nothing at the
 * bridge ({@link DiscordServers}). DMs never rest, nor anything while archived chats notify ({@link
 * Settings#NOTIFY_ARCHIVED}), nor while the archive is open. Waking goes in order: servers bring everything again, the
 * filter takes the rooms back, then a sync of only those rooms (no {@code since}) catches up on what they missed, their
 * unread counts too. One thread does it all, so leaving the archive mid catch-up finishes it before they rest again.
 */
final class ArchiveRest {
    /** Rooms per catch-up sync. */
    static final int CHUNK = 100;
    /** Events per room in a catch-up: the rest are in the room's history, from its {@code prev_batch}. */
    static final int CATCH_UP_LIMIT = 20;

    private final MatrixCore core;
    private final SyncProcessor proc;
    private final SyncFilter filter;
    private final DiscordServers discord;
    private final Consumer<SyncProcessor.Result> dispatch;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Threads.daemon("discord-rest"));
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Consumer<String> onSetting = key -> {
        if (key == null || key.equals(Settings.NOTIFY_ARCHIVED)) update();
    };
    private volatile boolean archiveOpen;
    private volatile Waking waking;
    /** The next run asked for, shared by everyone who asks before it starts; guarded by this. */
    private CompletableFuture<Void> next;
    /** Left out of the sync now. Worker thread only. */
    private Set<String> resting = Set.of();
    /** Awake but still to catch up on what they missed while resting (a catch-up that failed is tried again). Worker only. */
    private final Set<String> owed = new LinkedHashSet<>();

    ArchiveRest(MatrixCore core, SyncFilter filter, DiscordServers discord, Consumer<SyncProcessor.Result> dispatch) {
        this.core = core;
        this.proc = core.proc;
        this.filter = filter;
        this.discord = discord;
        this.dispatch = dispatch;
    }

    void start() { Settings.get().addListener(onSetting); }

    void stop() {
        Settings.get().removeListener(onSetting);
        worker.shutdownNow();
    }

    CompletableFuture<Void> setArchiveOpen(boolean open) {
        archiveOpen = open;
        return update();
    }

    Waking waking() { return waking; }

    void addListener(Runnable l) { listeners.add(l); }

    void removeListener(Runnable l) { listeners.remove(l); }

    /** Brings what rests in line with the archive and the settings now; done when that run has finished. */
    synchronized CompletableFuture<Void> update() {
        if (next == null) {
            next = new CompletableFuture<>();
            worker.execute(this::run);
        }
        return next;
    }

    private void run() {
        CompletableFuture<Void> done;
        synchronized (this) {
            done = next;
            next = null;
        }
        try {
            apply();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("Discord rest: " + e);
        } finally {
            done.complete(null);
        }
    }

    private void apply() throws InterruptedException {
        Set<String> want;
        Map<String, Boolean> guilds;
        synchronized (proc) {
            want = restingRooms(proc.rooms(), archiveOpen || Settings.get().notifyArchived());
            guilds = guildsResting(want);
        }
        for (String r : resting) if (!want.contains(r)) owed.add(r);
        owed.removeAll(want);
        List<String> resume = discord.toSet(guilds, false);
        // 1. Their servers bring everything again.
        for (int i = 0; i < resume.size(); i++) {
            show(Step.SERVERS, i, resume.size());
            discord.setGuildPaused(resume.get(i), false);
        }
        if (!resume.isEmpty()) show(Step.SERVERS, resume.size(), resume.size());
        // 2. The sync takes them back (or leaves the newly archived out).
        if (!want.equals(resting)) refilter(want);
        boolean wakes = !resume.isEmpty() || !owed.isEmpty();
        // 3. What they missed.
        catchUp();
        // 4. Servers that rest now bring nothing.
        for (String g : discord.toSet(guilds, true)) discord.setGuildPaused(g, true);
        if (wakes) show(Step.DONE, waking == null ? 0 : waking.total(), waking == null ? 0 : waking.total());
    }

    /** The sync leaves {@code want} out once a request has gone out so; a filter the server refused leaves nothing out. */
    private void refilter(Set<String> want) throws InterruptedException {
        try {
            await(filter.exclude(want));
            resting = want;
        } catch (java.io.IOException | MatrixException e) {
            System.err.println("Discord rest: the server didn't keep the filter, nothing rests: " + e);
            try {
                await(filter.exclude(Set.of()));
            } catch (java.io.IOException never) {
                throw new IllegalStateException(never); // the usual filter is never posted
            }
            owed.addAll(resting);
            resting = Set.of();
        }
    }

    private static void await(CompletableFuture<Void> f) throws InterruptedException {
        try {
            f.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    /** One sync for each {@link #CHUNK} of {@link #owed}, the most recently active first; what fails stays owed. */
    private void catchUp() {
        if (owed.isEmpty()) return;
        List<String> order;
        synchronized (proc) {
            order = new ArrayList<>(owed);
            order.sort(Comparator.comparing((String id) -> {
                MatrixRoom r = proc.room(id);
                return r == null ? java.time.Instant.EPOCH : r.chat.lastActivity();
            }, Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
        }
        show(Step.CHATS, 0, order.size());
        for (int from = 0; from < order.size(); from += CHUNK) {
            List<String> part = order.subList(from, Math.min(order.size(), from + CHUNK));
            try {
                ObjectNode f = SyncFilter.base();
                SyncFilter.roomList(f, "rooms", part);
                ((ObjectNode) f.path("room").path("timeline")).put("limit", CATCH_UP_LIMIT);
                f.putObject("account_data").putArray("not_types").add("*");
                f.putObject("presence").putArray("not_types").add("*");
                JsonNode resp = core.client.request("GET", CLIENT + "/sync?timeout=0&filter=" + enc(catchUpFilter(f)), null,
                        Duration.ofSeconds(180));
                SyncProcessor.Result r;
                synchronized (proc) {
                    r = proc.process(resp, false);
                }
                dispatch.accept(r);
                part.forEach(owed::remove);
                show(Step.CHATS, from + part.size(), order.size());
            } catch (Exception e) {
                System.err.println("Discord rest: catching up failed, tried again on the next sync: " + e);
                return;
            }
        }
    }

    /** Posted; inlined if the server won't keep it (a part is small enough for the URL). */
    private String catchUpFilter(ObjectNode f) {
        try {
            return filter.id(f);
        } catch (java.io.IOException | MatrixException e) {
            return f.toString();
        }
    }

    private void show(Step step, int done, int total) {
        waking = new Waking(step, done, total);
        for (Runnable l : listeners) {
            try {
                l.run();
            } catch (RuntimeException e) {
                System.err.println("Listener failed: " + e);
            }
        }
    }

    /**
     * The rooms that rest: archived Discord servers' spaces and archived rooms inside a Discord server (a channel, a
     * category), never a DM or group DM (in no server), a bot's chat, an invite or another network's room. None while
     * {@code awake}. Caller holds the processor's lock.
     */
    static Set<String> restingRooms(Collection<MatrixRoom> rooms, boolean awake) {
        if (awake) return Set.of();
        Set<String> servers = new HashSet<>();
        for (MatrixRoom r : rooms) if (isServerSpace(r)) servers.add(r.id);
        Set<String> inServer = new HashSet<>(servers);
        for (MatrixRoom r : rooms) {
            if (servers.contains(r.id)) inServer.addAll(r.chat.spaceChildren());
            for (String p : r.chat.spaceParents()) if (servers.contains(p)) inServer.add(r.id);
        }
        Set<String> out = new HashSet<>();
        for (MatrixRoom r : rooms) {
            if (r.chat.archived() && inServer.contains(r.id) && r.chat.network() == Network.DISCORD && !r.chat.bridgeBot()
                    && !r.invite) {
                out.add(r.id);
            }
        }
        return out;
    }

    private static boolean isServerSpace(MatrixRoom r) {
        return r.space && r.chat.network() == Network.DISCORD && !r.chat.personalSpace();
    }

    /** Each Discord server whose space is known → whether it rests. Caller holds the processor's lock. */
    private Map<String, Boolean> guildsResting(Set<String> want) {
        Map<String, Boolean> out = new HashMap<>();
        for (MatrixRoom r : proc.rooms()) {
            String g = r.space ? discord.guildOf(r.id) : null;
            if (g != null) out.put(g, want.contains(r.id));
        }
        return out;
    }
}
