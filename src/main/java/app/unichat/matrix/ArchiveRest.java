package app.unichat.matrix;

import app.unichat.model.Network;
import app.unichat.service.ArchiveWaking.Step;
import app.unichat.service.ArchiveWaking.Waking;
import app.unichat.settings.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static app.unichat.matrix.MatrixClient.CLIENT;
import static app.unichat.support.Urls.enc;

/**
 * Archived Discord servers and channels rest out of the sync: they're most of the person's thousands of rooms, and
 * each sync made the server walk them all. {@link SyncFilter} leaves them out. DMs never rest, nor the chat open in the
 * window, nor anything while archived chats notify ({@link Settings#NOTIFY_ARCHIVED}) or the archive is open.
 *
 * <p>All of it runs on the sync thread, before a request ({@link #beforeRequest}): the filter changes, then one part
 * of the catch-up (a sync of only woken rooms, no {@code since}), then the request goes out with the {@code since} it
 * had. Nothing live is processed between a catch-up and the request after it, and that request brings again whatever
 * came after its {@code since}, so a catch-up never overwrites anything newer. A catch-up under way finishes before
 * what rests changes (leaving the archive mid-way); one that fails is tried again ({@link Retry}). Resting rooms'
 * own account data (tags: unarchived elsewhere) is asked for every {@link #TAGS_EVERY}, since the filter leaves it out
 * too. What servers bring at the bridge is {@link ArchivedServers}'.
 */
final class ArchiveRest {
    /** Rooms per catch-up sync. */
    static final int CHUNK = 100;
    /** Events per room in a catch-up: the rest are in the room's history, from its {@code prev_batch}. */
    static final int CATCH_UP_LIMIT = 20;
    /**
     * How often resting rooms' tags are asked for. {@code room.not_rooms} leaves their account data out of the sync
     * as well (the spec applies it before every part of the room filter), and moving the rooms to each part's own
     * {@code not_rooms} is untested for what the server saves; a sync of only their tags this often walks them seldom.
     */
    static final Duration TAGS_EVERY = Duration.ofMinutes(5);

    private final MatrixCore core;
    private final SyncProcessor proc;
    private final SyncFilter filter;
    private final DiscordServers discord;
    private final ArchivedServers servers;
    /** A catch-up's answer, with the rooms it was for. */
    private final BiConsumer<SyncProcessor.Result, Collection<String>> caughtUp;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    @SuppressWarnings("UnnecessaryLambda") // one instance: removeListener takes away what addListener added
    private final Consumer<String> onSetting = key -> {
        if (key == null || key.equals(Settings.NOTIFY_ARCHIVED)) { var _ = update(); }
    };
    private volatile boolean archiveOpen;
    private volatile String openChat;
    private volatile Waking waking;
    private volatile Runnable wakeLoop = () -> { };
    private volatile Supplier<Instant> clock = Instant::now;
    /** How long a catch-up or tags sync may take: they hold the sync thread, so not a full sync's minutes. */
    private volatile Duration sideTimeout = Duration.ofSeconds(30);
    /** Where the main sync is ({@code next_batch}): rooms that start resting have their tags asked from there. */
    private final Supplier<String> mainSince;
    /** The rooms that rest but for the chat open in the window; published for {@link #setOpenChat}. */
    private volatile Set<String> restable = Set.of();
    /** Asks made, and those waiting for a round that has seen them; guarded by this. */
    private long asked;
    private final List<Waiter> waiters = new ArrayList<>();
    /** A catch-up under way, not failed: what rests waits for it. Guarded by this (the bar decides with it). */
    private boolean catching;

    // Sync thread only.
    private Set<String> resting = Set.of();
    /** Awake, still to catch up on what they missed while resting. */
    private final Set<String> owed = new LinkedHashSet<>();
    private int partsDone;
    private int partsTotal;
    private Retry catchUpRetry;
    private Retry filterRetry;
    private Instant tagsDue;
    private String tagsSince;

    private record Waiter(long ask, CompletableFuture<Void> done) {}

    ArchiveRest(MatrixCore core, SyncFilter filter, DiscordServers discord,
                BiConsumer<SyncProcessor.Result, Collection<String>> caughtUp, Supplier<String> mainSince) {
        this.mainSince = mainSince;
        this.core = core;
        this.proc = core.proc;
        this.filter = filter;
        this.discord = discord;
        this.servers = new ArchivedServers(core, discord, this::resuming);
        this.caughtUp = caughtUp;
    }

    /** What makes the sync loop go round at once (its long poll dropped). */
    void onWake(Runnable r) { wakeLoop = r; }

    /** Tests move time on to a retry, instead of waiting for it. */
    void useClock(Supplier<Instant> c) { clock = c; }

    /** Tests give a catch-up no time at all, to see what follows a server that doesn't answer. */
    void useTimeout(Duration d) { sideTimeout = d; }

    void start() { Settings.get().addListener(onSetting); }

    void stop() {
        Settings.get().removeListener(onSetting);
        servers.stop();
        synchronized (this) {
            for (Waiter w : waiters) w.done().cancel(false);
            waiters.clear();
        }
    }

    CompletableFuture<Void> setArchiveOpen(boolean open) {
        archiveOpen = open;
        CompletableFuture<Void> f = update();
        if (open) return f;
        // Resting first, then the bridge; reopened meanwhile, the servers brought back stay back.
        return f.thenCompose(x -> archiveOpen ? CompletableFuture.completedFuture(null) : servers.archiveLeft());
    }

    /** The chat shown in the window: it never rests, or its new messages wouldn't come (opened from a search, a folder). */
    CompletableFuture<Void> setOpenChat(String chatId) {
        String before = openChat;
        openChat = chatId;
        // Only a chat that rests, or rested until now, changes anything: any other mustn't drop the long poll.
        Set<String> r = restable;
        boolean matters = (chatId != null && r.contains(chatId)) || (before != null && r.contains(before));
        return matters ? update() : CompletableFuture.completedFuture(null);
    }

    /** A chat opened in Archivados: an archived server, or a channel inside one, brings everything again until leaving. */
    CompletableFuture<Void> archivedChatOpened(String chatId) {
        return archiveOpen ? servers.opened(chatId) : CompletableFuture.completedFuture(null);
    }

    /** What's archived changed, or Discord connected: what rests, and what each server brings, follow. */
    CompletableFuture<Void> archivedChanged() { return CompletableFuture.allOf(update(), servers.update()); }

    Waking waking() { return waking; }

    void addListener(Runnable l) { listeners.add(l); }

    void removeListener(Runnable l) { listeners.remove(l); }

    /** Asks the sync loop to bring what rests in line now; done after a round that saw it with nothing left to do. */
    CompletableFuture<Void> update() {
        CompletableFuture<Void> f = new CompletableFuture<>();
        synchronized (this) {
            waiters.add(new Waiter(++asked, f));
        }
        wakeLoop.run();
        return f;
    }

    /**
     * On the sync thread, before each request (once the first sync is in): tags if due, what rests, a part of the
     * catch-up. True when more is left for the next round, which then shouldn't long-poll.
     */
    boolean beforeRequest() {
        long seen;
        synchronized (this) {
            seen = asked;
        }
        boolean more = false;
        try {
            tags();
            if (!catchingNow()) settle();
            catchUpPart();
            if (!catchingNow()) settle(); // what changed while it caught up: leaving the archive rests at once after
            more = !owed.isEmpty() && catchUpRetry == null;
        } catch (RuntimeException e) {
            System.err.println("Discord rest: " + e);
        } finally {
            if (!more) {
                synchronized (this) {
                    waiters.removeIf(w -> {
                        if (w.ask() > seen) return false;
                        w.done().complete(null);
                        return true;
                    });
                }
            }
        }
        return more;
    }

    /** The server no longer knows the filter the sync named: everything comes again, and what rested catches up. */
    void filterForgotten(String id) {
        filter.forget(id);
        owed.addAll(resting);
        resting = Set.of();
    }

    private synchronized boolean catchingNow() { return catching; }

    /** The sync leaves out what should rest now; posting a filter that fails is tried again later. */
    private void settle() {
        Set<String> want;
        synchronized (proc) {
            want = restingRooms();
        }
        if (!want.equals(resting)) {
            Instant now = clock.get();
            boolean wakes = !want.containsAll(resting);
            boolean mayPost = filterRetry == null || filterRetry.due(now);
            // Without a post: everything wakes (the usual filter), or what rests stays as it is (its filter is known).
            Set<String> fallback = wakes ? Set.of() : resting;
            Set<String> target = want.isEmpty() || mayPost ? want : fallback;
            try {
                filter.exclude(target);
                if (target == want) filterRetry = null;
            } catch (java.io.InterruptedIOException e) {
                // The computer woke mid post: not a refusal. The request after this takes the interrupt; the next round posts.
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | MatrixException e) {
                System.err.println("Discord rest: the server didn't keep the filter, tried again later: " + e);
                filterRetry = Retry.after(filterRetry, now);
                target = fallback;
                excludeKnown(target);
            }
            for (String r : resting) if (!target.contains(r)) owed.add(r);
            // Their tags from the point the main sync stops bringing them, or a change between the two is never heard.
            if (!resting.containsAll(target)) {
                // Those resting already first catch up on their tags up to now, from where their last tags sync was.
                // If that fails, no point at all: the next tags sync brings everything, always safe (the old point may
                // be newer than a change on a room just come to rest). Never synced yet: the same, already.
                if (tagsSince != null && !resting.isEmpty() && !tagsSync(resting)) tagsSince = null;
                else if (tagsSince != null || resting.isEmpty()) tagsSince = mainSince.get();
                if (tagsDue == null) tagsDue = now.plus(TAGS_EVERY);
            }
            resting = target;
        }
        owed.removeAll(resting);
        if (owed.isEmpty() && catchUpRetry != null) {
            // What failed to catch up rests again (the archive left): nothing to retry, nothing to say.
            catchUpRetry = null;
            show(null);
        }
    }

    /** A filter already posted (or none): never asks the server. */
    private void excludeKnown(Set<String> rooms) {
        try {
            filter.exclude(rooms);
        } catch (IOException | MatrixException e) {
            try {
                filter.exclude(Set.of());
            } catch (IOException never) {
                throw new IllegalStateException(never); // the usual filter is never posted
            }
            for (String r : resting) owed.add(r);
            resting = Set.of();
        }
    }

    /** One {@link #CHUNK} of what's owed, in the rooms' order (the same rooms are the same filters), if its time has come. */
    private void catchUpPart() {
        if (owed.isEmpty()) return;
        Instant now = clock.get();
        if (catchUpRetry != null && !catchUpRetry.due(now)) return;
        synchronized (this) {
            if (!catching) {
                catching = true;
                partsDone = 0;
                partsTotal = owed.size();
                showLocked(new Waking(Step.CHATS, 0, partsTotal));
            }
        }
        List<String> part = new ArrayList<>(new TreeSet<>(owed)).subList(0, Math.min(CHUNK, owed.size()));
        try {
            fetch(part);
            part.forEach(owed::remove);
            partsDone += part.size();
            catchUpRetry = null;
            synchronized (this) {
                if (owed.isEmpty()) catching = false;
                showLocked(new Waking(owed.isEmpty() ? Step.DONE : Step.CHATS, partsDone, partsTotal));
            }
        } catch (java.io.InterruptedIOException e) {
            // The computer woke (MatrixChatService.wake): not a failure. The request after this takes the interrupt,
            // and the next round goes on where this part was.
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            System.err.println("Discord rest: catching up failed, tried again later: " + e);
            catchUpRetry = Retry.after(catchUpRetry, now);
            synchronized (this) {
                catching = false;
                showLocked(new Waking(Step.RETRYING, partsDone, partsTotal));
            }
        }
    }

    private void fetch(List<String> part) throws IOException {
        ObjectNode f = SyncFilter.base();
        SyncFilter.roomList(f, "rooms", part);
        ((ObjectNode) f.path("room").path("timeline")).put("limit", CATCH_UP_LIMIT);
        f.putObject("account_data").putArray("not_types").add("*");
        f.putObject("presence").putArray("not_types").add("*");
        JsonNode resp = sync(f, null);
        SyncProcessor.Result r;
        synchronized (proc) {
            r = proc.process(resp, false);
        }
        caughtUp.accept(r, part);
    }

    /** The resting rooms' own account data, if due: only that is taken, never their timelines. */
    private void tags() {
        Instant now = clock.get();
        if (resting.isEmpty()) {
            tagsDue = null;
            return;
        }
        if (tagsDue == null) tagsDue = now.plus(TAGS_EVERY);
        if (now.isBefore(tagsDue)) return;
        tagsDue = now.plus(TAGS_EVERY);
        if (!tagsSync(resting) && Thread.currentThread().isInterrupted()) tagsDue = now; // woken mid-way: next round
    }

    /** A tags-only sync of {@code rooms} from {@link #tagsSince}; false when it didn't come through. */
    private boolean tagsSync(Set<String> rooms) {
        ObjectNode f = MatrixClient.JSON.createObjectNode();
        ObjectNode room = f.putObject("room");
        SyncFilter.roomList(f, "rooms", rooms);
        room.putObject("timeline").put("limit", 0);
        room.putObject("state").putArray("not_types").add("*");
        room.putObject("ephemeral").putArray("not_types").add("*");
        f.putObject("account_data").putArray("not_types").add("*");
        f.putObject("presence").putArray("not_types").add("*");
        try {
            JsonNode resp = sync(f, tagsSince);
            ObjectNode join = MatrixClient.JSON.createObjectNode();
            for (Map.Entry<String, JsonNode> e : (Iterable<Map.Entry<String, JsonNode>>) () -> resp.path("rooms").path("join").fields()) {
                JsonNode data = e.getValue().path("account_data");
                if (!data.path("events").isEmpty() && rooms.contains(e.getKey())) join.putObject(e.getKey()).set("account_data", data);
            }
            tagsSince = resp.path("next_batch").asText(null);
            if (join.isEmpty()) return true;
            ObjectNode only = MatrixClient.JSON.createObjectNode();
            only.putObject("rooms").set("join", join);
            SyncProcessor.Result r;
            synchronized (proc) {
                r = proc.process(only, false);
            }
            caughtUp.accept(r, List.of());
            return true;
        } catch (java.io.InterruptedIOException e) {
            Thread.currentThread().interrupt(); // the computer woke: the request after this takes it
            return false;
        } catch (IOException | RuntimeException e) {
            System.err.println("Discord rest: resting rooms' tags failed, tried again later: " + e);
            return false;
        }
    }

    /** A sync with filter {@code f}, posted; inlined if the server won't keep it or doesn't know its id any more. */
    private JsonNode sync(ObjectNode f, String since) throws IOException {
        String tail = "&timeout=0" + (since == null ? "" : "&since=" + enc(since));
        String id;
        try {
            id = filter.id(f);
        } catch (IOException | MatrixException e) {
            id = null;
        }
        if (id != null) {
            try {
                return core.client.request("GET", CLIENT + "/sync?filter=" + enc(id) + tail, null, sideTimeout);
            } catch (MatrixException e) {
                if (!SyncFilter.unknown(e)) throw e;
                filter.forget(id);
            }
        }
        return core.client.request("GET", CLIENT + "/sync?filter=" + enc(f.toString()) + tail, null, sideTimeout);
    }

    /** "Reactivando <server>…" while a server comes back; once back, "Al día" unless a catch-up is still going. */
    private synchronized void resuming(String server) {
        if (server != null) showLocked(new Waking(Step.SERVER, 0, 0, server));
        else if (!catching) showLocked(new Waking(Step.DONE, 0, 0));
    }

    private synchronized void show(Waking w) { showLocked(w); }

    /** Caller holds this: the bar's state is decided in one place at a time. */
    private void showLocked(Waking w) {
        waking = w;
        for (Runnable l : listeners) {
            try {
                l.run();
            } catch (RuntimeException e) {
                System.err.println("Listener failed: " + e);
            }
        }
    }

    /**
     * The rooms that rest: archived Discord rooms inside a server (its space, a category, a channel however deep),
     * never a DM or group DM (in no server), a bot's chat, an invite, another network's room or the chat open in the
     * window. None while the archive is open or archived chats notify. Caller holds the processor's lock.
     */
    private Set<String> restingRooms() {
        if (archiveOpen || Settings.get().notifyArchived()) {
            restable = Set.of();
            return Set.of();
        }
        Map<String, String> inServer = discord.serverSpaces();
        Set<String> out = new HashSet<>();
        for (MatrixRoom r : proc.rooms()) {
            if (r.chat.archived() && inServer.containsKey(r.id) && r.chat.network() == Network.DISCORD && !r.chat.bridgeBot()
                    && !r.invite) {
                out.add(r.id);
            }
        }
        restable = Set.copyOf(out);
        out.remove(openChat);
        return out;
    }
}
