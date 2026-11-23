package app.unichat.matrix;

import app.unichat.support.Threads;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * What each Discord server brings at the bridge: an archived server brings nothing ({@code guilds bridging-mode
 * nothing}), whatever "notify archived" says, and the rest bring messages again. Opening an archived server, or a channel
 * inside one, while in Archivados brings that one server back ({@code create-on-message}) until the person leaves
 * Archivados; then it brings nothing again. Opening Archivados alone sends no command: waking 123 servers one by one
 * flooded the bot chat and took minutes, and the filter alone is what saves the sync. Commands go one at a time, on a
 * thread of their own, so a catch-up never waits for the bot nor the bot for a catch-up.
 */
final class ArchivedServers {
    private final SyncProcessor proc;
    private final DiscordServers discord;
    /** Told the name of a server as it's brought back, and null once it has. */
    private final Consumer<String> resuming;
    private final ExecutorService bridge = Executors.newSingleThreadExecutor(Threads.daemon("discord-servers"));
    /** Guild ids brought back while Archivados is open; guarded by this. */
    private final Set<String> resumed = new HashSet<>();
    /** The next run asked for, shared by everyone who asks before it starts; guarded by this. */
    private CompletableFuture<Void> next;

    ArchivedServers(MatrixCore core, DiscordServers discord, Consumer<String> resuming) {
        this.proc = core.proc;
        this.discord = discord;
        this.resuming = resuming;
    }

    void stop() { bridge.shutdownNow(); }

    /** The person opened {@code chatId} in Archivados: its server, if archived, brings everything again for now. */
    CompletableFuture<Void> opened(String chatId) {
        String guild;
        synchronized (proc) {
            String space = serverSpace(chatId, new HashSet<>());
            guild = space == null ? null : discord.guildOf(space);
        }
        if (guild != null) {
            synchronized (this) {
                resumed.add(guild);
            }
        }
        return update();
    }

    /** Archivados was left: the servers it brought back bring nothing again. */
    CompletableFuture<Void> archiveLeft() {
        synchronized (this) {
            resumed.clear();
        }
        return update();
    }

    /** Sets every known server at the bridge as it should be now; done when that run has finished. */
    synchronized CompletableFuture<Void> update() {
        if (next == null) {
            next = new CompletableFuture<>();
            bridge.execute(this::run);
        }
        return next;
    }

    private void run() {
        CompletableFuture<Void> done;
        Set<String> back;
        synchronized (this) {
            done = next;
            next = null;
            back = Set.copyOf(resumed);
        }
        try {
            Map<String, Boolean> paused = new HashMap<>();
            Map<String, String> names = new HashMap<>();
            synchronized (proc) {
                for (MatrixRoom r : proc.rooms()) {
                    String g = r.space ? discord.guildOf(r.id) : null;
                    if (g == null) continue;
                    paused.put(g, r.chat.archived() && !back.contains(g));
                    names.put(g, r.chat.title());
                }
            }
            List<String> resume = discord.toSet(paused, false);
            for (String g : resume) {
                resuming.accept(names.get(g));
                discord.setGuildPaused(g, false);
            }
            if (!resume.isEmpty()) resuming.accept(null);
            for (String g : discord.toSet(paused, true)) discord.setGuildPaused(g, true);
        } catch (RuntimeException e) {
            System.err.println("Discord servers: " + e);
        } finally {
            done.complete(null);
        }
    }

    /** The space of the Discord server {@code id} is (or is inside, through categories), or null. Caller holds the lock. */
    private String serverSpace(String id, Set<String> seen) {
        if (!seen.add(id)) return null;
        if (discord.guildOf(id) != null) return id;
        MatrixRoom r = proc.room(id);
        if (r == null) return null;
        for (String p : r.chat.spaceParents()) {
            String s = serverSpace(p, seen);
            if (s != null) return s;
        }
        for (MatrixRoom x : proc.rooms()) {
            if (!x.space || !x.chat.spaceChildren().contains(id)) continue;
            String s = serverSpace(x.id, seen);
            if (s != null) return s;
        }
        return null;
    }
}
