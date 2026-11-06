package app.unichat.matrix;

import app.unichat.model.Chat;
import app.unichat.service.ChatService;
import app.unichat.service.ChatService.Listener;
import app.unichat.service.PreferencesFile;
import app.unichat.service.PreferencesFile.Flag;
import app.unichat.service.QuietRule;
import app.unichat.settings.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static app.unichat.matrix.BridgeProtocol.*;
import static app.unichat.matrix.MatrixClient.CLIENT;
import static app.unichat.matrix.MatrixClient.enc;
import static app.unichat.matrix.MatrixLogin.userMessage;

/** What you change about a chat: read state, favourite, archive, mute, pin, reminder, join and leave. */
final class ChatActions {
    private final MatrixCore core;
    private final MatrixClient client;
    private final SyncProcessor proc;
    private final String userId;
    private final ChatService service;
    private final DiscordServers discord;
    private final PreferencesFile prefs;
    /** The last mute rule write queued; guarded by the processor's lock. */
    private CompletableFuture<Void> muteWrites = CompletableFuture.completedFuture(null);

    ChatActions(MatrixCore core, ChatService service, DiscordServers discord, PreferencesFile prefs) {
        this.core = core;
        this.client = core.client;
        this.proc = core.proc;
        this.userId = core.userId;
        this.service = service;
        this.discord = discord;
        this.prefs = prefs;
    }

    private final Map<String, String> lastReadSent = new ConcurrentHashMap<>();

    /**
     * Clears unread state and moves the read marker. Sends a public m.read receipt only when
     * {@link Settings#sendReadReceipts()} is on; otherwise a private receipt (m.read.private).
     */
    void markRead(String chatId) {
        String last;
        boolean wasMarked;
        synchronized (proc) {
            MatrixRoom r = proc.room(chatId);
            if (r == null) return;
            last = proc.lastEventId(chatId);
            wasMarked = r.chat.markedUnread();
            boolean wasUnread = r.chat.unreadCount() > 0 || r.chat.mentionCount() > 0;
            r.chat.setUnreadCount(0);
            r.chat.setMentionCount(0);
            r.chat.setMarkedUnread(false);
            if (!wasMarked && !wasUnread && last != null && last.equals(lastReadSent.get(chatId))) return;
        }
        core.fire(Listener::chatsChanged);
        core.io.execute(() -> {
            try {
                if (last != null) {
                    ObjectNode body = MatrixClient.JSON.createObjectNode().put("m.fully_read", last);
                    body.put(Settings.get().sendReadReceipts() ? "m.read" : "m.read.private", last);
                    client.post(CLIENT + "/rooms/" + enc(chatId) + "/read_markers", body);
                    lastReadSent.put(chatId, last);
                }
                if (wasMarked) putRoomAccountData(chatId, "m.marked_unread", MatrixClient.JSON.createObjectNode().put("unread", false));
            } catch (Exception e) {
                System.err.println("markRead failed: " + e);
            }
        });
    }

    CompletableFuture<Void> setMarkedUnread(String chatId, boolean unread) {
        return optimistic(chatId, Chat::markedUnread, Chat::setMarkedUnread, unread,
                () -> putRoomAccountData(chatId, "m.marked_unread", MatrixClient.JSON.createObjectNode().put("unread", unread)));
    }

    CompletableFuture<Void> setFavorite(String chatId, boolean favorite) {
        return choose(chatId, Flag.FAVORITE, favorite);
    }

    CompletableFuture<Void> setLowPriority(String chatId, boolean lowPriority) {
        return choose(chatId, Flag.LOW_PRIORITY, lowPriority);
    }

    CompletableFuture<Void> setPinned(String chatId, boolean pinned) {
        return choose(chatId, Flag.PINNED, pinned);
    }

    CompletableFuture<Void> setArchived(String chatId, boolean archived) {
        return quiet(chatId, archived);
    }

    CompletableFuture<Void> setMuted(String chatId, boolean muted) {
        return quiet(chatId, muted);
    }

    /**
     * Muted = archived ({@link app.unichat.service.QuietRule}): both, for the chat and a server's channels. The file
     * takes it right away and the mute rule follows; a rule that can't be written doesn't undo the choice, it's
     * retried from {@link #applyMutes}.
     */
    private CompletableFuture<Void> quiet(String chatId, boolean on) {
        List<String> targets = QuietRule.targets(service, chatId);
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        String guild;
        synchronized (proc) {
            prefs.batch(() -> {
                for (String id : targets) {
                    boolean found = changeNow(id, key -> {
                        prefs.choose(key, Flag.ARCHIVED, on);
                        prefs.choose(key, Flag.MUTED, on);
                    });
                    if (found) writes.add(queueMuteWrite(id, on));
                }
            });
            guild = discord.guildOf(chatId);
        }
        if (writes.isEmpty()) return chatNotFound();
        core.fire(Listener::chatsChanged);
        if (guild != null) discord.setGuildPaused(guild, on);
        return CompletableFuture.allOf(writes.toArray(new CompletableFuture[0]));
    }

    /** Muted on the phone: archived too, as a rule and not a choice, so it never overrides what the person chose. */
    void followArchived(List<String> chatIds) {
        if (chatIds.isEmpty()) return;
        synchronized (proc) {
            follow(chatIds, Flag.ARCHIVED);
        }
        core.fire(Listener::chatsChanged);
    }

    /**
     * Arrived inside an archived server: quiet like its siblings, as a rule and not a choice. What the person chose
     * stays, however often this runs (every start), and the phone can still change the rest. {@link #applyMutes} writes
     * the rule. A chat that stays out of the archive doesn't pause its Discord server.
     */
    void followQuiet(List<String> chatIds) {
        if (chatIds.isEmpty()) return;
        Set<String> targets = new LinkedHashSet<>();
        for (String id : chatIds) targets.addAll(QuietRule.targets(service, id));
        List<String> guilds = new ArrayList<>();
        synchronized (proc) {
            follow(targets, Flag.ARCHIVED, Flag.MUTED);
            for (String id : chatIds) {
                String guild = discord.guildOf(id);
                if (guild != null && proc.room(id).chat.archived()) guilds.add(guild);
            }
        }
        core.fire(Listener::chatsChanged);
        for (String guild : guilds) discord.setGuildPaused(guild, true);
    }

    /** Caller holds the processor's lock. */
    private void follow(Collection<String> chatIds, Flag... flags) {
        prefs.batch(() -> {
            for (String id : chatIds) {
                changeNow(id, key -> {
                    for (Flag flag : flags) prefs.follow(key, flag, true);
                });
            }
        });
    }

    /** Writes the rules that differ from the file; a room with a write under way or waiting to retry stays out. */
    void applyMutes() {
        synchronized (proc) {
            proc.mutesToApply().forEach(this::queueMuteWrite);
        }
    }

    /**
     * Mute writes go one at a time, in order, so a room's last choice is the one that stays and thousands of rooms
     * (a new installation) don't each take a thread. Caller holds the processor's lock.
     */
    private CompletableFuture<Void> queueMuteWrite(String chatId, boolean muted) {
        proc.muteWriteStarted(chatId);
        muteWrites = muteWrites.thenRunAsync(() -> writeMuteRule(chatId, muted), core.io);
        return muteWrites;
    }

    /** The queue of mute writes as it stands: done when those are. */
    CompletableFuture<Void> muteWritesDone() {
        synchronized (proc) {
            return muteWrites;
        }
    }

    /** Never throws: a failure is retried later, and the queue must go on. */
    private void writeMuteRule(String chatId, boolean muted) {
        boolean ok = false;
        try {
            String rule = CLIENT + "/pushrules/global/room/" + enc(chatId);
            if (muted) {
                ObjectNode body = MatrixClient.JSON.createObjectNode();
                body.putArray("actions");
                client.put(rule, body);
            } else {
                deleteIgnoringMissing(rule);
                deleteIgnoringMissing(CLIENT + "/pushrules/global/override/" + enc(chatId));
            }
            ok = true;
        } catch (Exception e) {
            System.err.println("Could not write the mute rule, will retry: " + e);
        } finally {
            synchronized (proc) {
                proc.muteWriteFinished(chatId, muted, ok, Instant.now());
            }
        }
    }

    private void deleteIgnoringMissing(String path) throws IOException {
        try {
            client.delete(path);
        } catch (MatrixException e) {
            if (e.status() != 404) throw e;
        }
    }

    CompletableFuture<Void> setReminder(String chatId, Instant at) {
        return change(chatId, key -> prefs.chooseReminder(key, at));
    }

    private CompletableFuture<Void> choose(String chatId, Flag flag, boolean value) {
        return change(chatId, key -> prefs.choose(key, flag, value));
    }

    /** A preference changes in the file only: applied to the chat at once, nothing to wait for. */
    private CompletableFuture<Void> change(String chatId, Consumer<String> edit) {
        boolean found;
        synchronized (proc) {
            found = changeNow(chatId, edit);
        }
        if (!found) return chatNotFound();
        core.fire(Listener::chatsChanged);
        return CompletableFuture.completedFuture(null);
    }

    /** Caller holds the processor's lock. False if there's no such chat. */
    private boolean changeNow(String chatId, Consumer<String> edit) {
        MatrixRoom room = proc.room(chatId);
        if (room == null) return false;
        edit.accept(proc.prefKey(room));
        proc.showPreferences(room);
        return true;
    }

    private static CompletableFuture<Void> chatNotFound() {
        return CompletableFuture.failedFuture(new MatrixException(0, null, "Chat no encontrado"));
    }

    CompletableFuture<Void> joinChat(String roomId) {
        return core.run(() -> client.post(CLIENT + "/join/" + enc(roomId), null));
    }

    CompletableFuture<Map<String, String>> spaceNames(String spaceId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonNode r = client.get("/_matrix/client/v1/rooms/" + enc(spaceId) + "/hierarchy?max_depth=1&limit=100");
                Map<String, String> names = new java.util.LinkedHashMap<>();
                for (JsonNode room : r.path("rooms")) {
                    String id = room.path("room_id").asText("");
                    String name = room.path("name").asText("");
                    if (name.isBlank()) name = room.path("canonical_alias").asText("");
                    if (!id.equals(spaceId) && !name.isBlank()) names.put(id, name);
                }
                return names;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, core.io);
    }

    CompletableFuture<Void> leaveChat(String chatId) {
        return core.run(() -> {
            client.post(CLIENT + "/rooms/" + enc(chatId) + "/leave", null);
            try {
                client.post(CLIENT + "/rooms/" + enc(chatId) + "/forget", null);
            } catch (Exception ignored) {
                // forgetting is optional
            }
            synchronized (proc) {
                proc.removeRoom(chatId);
            }
            core.fire(Listener::chatsChanged);
            core.fire(Listener::accountsChanged);
        });
    }

    private void putRoomAccountData(String chatId, String type, ObjectNode content) throws IOException {
        client.put(CLIENT + "/user/" + enc(userId) + "/rooms/" + enc(chatId) + "/account_data/" + enc(type), content);
    }

    /** Applies a chat field change locally, sends it, and reverts on failure. */
    private <T> CompletableFuture<Void> optimistic(String chatId, java.util.function.Function<Chat, T> getter,
                                                   java.util.function.BiConsumer<Chat, T> setter, T value, MatrixCore.IoAction call) {
        Chat c = core.chat(chatId);
        if (c == null) return chatNotFound();
        T old;
        synchronized (proc) {
            old = getter.apply(c);
            setter.accept(c, value);
        }
        core.fire(Listener::chatsChanged);
        return core.run(() -> {
            try {
                call.run();
            } catch (Exception e) {
                synchronized (proc) {
                    setter.accept(c, old);
                }
                core.fire(Listener::chatsChanged);
                throw e;
            }
        });
    }

    void checkReminders() {
        if (!core.initialSyncDone) return;
        Instant now = Instant.now();
        List<Chat> due = new ArrayList<>();
        synchronized (proc) {
            for (MatrixRoom r : proc.rooms()) {
                Instant at = r.chat.reminderAt();
                if (at != null && !at.isAfter(now)) due.add(r.chat);
            }
        }
        for (Chat c : due) {
            setReminder(c.id(), null).exceptionally(e -> null);
            core.fire(l -> l.reminderDue(c));
        }
    }
}
