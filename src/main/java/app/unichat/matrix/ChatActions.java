package app.unichat.matrix;

import app.unichat.model.Chat;
import app.unichat.service.ChatService;
import app.unichat.service.ChatService.Listener;
import app.unichat.service.QuietRule;
import app.unichat.settings.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

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

    ChatActions(MatrixCore core, ChatService service, DiscordServers discord) {
        this.core = core;
        this.client = core.client;
        this.proc = core.proc;
        this.userId = core.userId;
        this.service = service;
        this.discord = discord;
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
        return optimistic(chatId, Chat::favorite, Chat::setFavorite, favorite, () -> setTag(chatId, "m.favourite", favorite));
    }

    CompletableFuture<Void> setLowPriority(String chatId, boolean lowPriority) {
        return optimistic(chatId, Chat::lowPriority, Chat::setLowPriority, lowPriority,
                () -> setTag(chatId, "m.lowpriority", lowPriority));
    }

    CompletableFuture<Void> setArchived(String chatId, boolean archived) {
        return quiet(chatId, archived);
    }

    /** Muted = archived ({@link app.unichat.service.QuietRule}): both, for the chat and a server's channels. */
    CompletableFuture<Void> quiet(String chatId, boolean on) {
        List<CompletableFuture<Void>> all = new ArrayList<>();
        for (String id : QuietRule.targets(service, chatId)) {
            all.add(archiveOnly(id, on));
            all.add(muteOnly(id, on));
        }
        String guild;
        synchronized (proc) {
            guild = discord.guildOf(chatId);
        }
        if (guild != null) discord.setGuildPaused(guild, on);
        return CompletableFuture.allOf(all.toArray(new CompletableFuture[0]));
    }

    CompletableFuture<Void> archiveOnly(String chatId, boolean archived) {
        return optimistic(chatId, Chat::archived, Chat::setArchived, archived,
                () -> setTag(chatId, SyncProcessor.TAG_ARCHIVED, archived));
    }

    CompletableFuture<Void> setPinned(String chatId, boolean pinned) {
        return optimistic(chatId, Chat::pinned, Chat::setPinned, pinned, () -> setTag(chatId, SyncProcessor.TAG_PINNED, pinned));
    }

    CompletableFuture<Void> setMuted(String chatId, boolean muted) {
        return quiet(chatId, muted);
    }

    private CompletableFuture<Void> muteOnly(String chatId, boolean muted) {
        boolean old;
        synchronized (proc) {
            Chat c = core.chat(chatId);
            old = c != null && c.muted();
            proc.setMutedLocal(chatId, muted);
        }
        core.fire(Listener::chatsChanged);
        return core.run(() -> {
            String rule = CLIENT + "/pushrules/global/room/" + enc(chatId);
            try {
                if (muted) {
                    ObjectNode body = MatrixClient.JSON.createObjectNode();
                    body.putArray("actions");
                    client.put(rule, body);
                } else {
                    deleteIgnoringMissing(rule);
                    deleteIgnoringMissing(CLIENT + "/pushrules/global/override/" + enc(chatId));
                }
            } catch (Exception e) {
                synchronized (proc) {
                    proc.setMutedLocal(chatId, old);
                }
                core.fire(Listener::chatsChanged);
                throw e;
            }
        });
    }

    private void deleteIgnoringMissing(String path) throws IOException {
        try {
            client.delete(path);
        } catch (MatrixException e) {
            if (e.status() != 404) throw e;
        }
    }

    CompletableFuture<Void> setReminder(String chatId, Instant at) {
        ObjectNode body = MatrixClient.JSON.createObjectNode();
        if (at != null) body.put("ts", at.toEpochMilli());
        return optimistic(chatId, Chat::reminderAt, Chat::setReminderAt, at,
                () -> putRoomAccountData(chatId, SyncProcessor.REMINDER_TYPE, body));
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

    private void setTag(String chatId, String tag, boolean on) throws IOException {
        String path = CLIENT + "/user/" + enc(userId) + "/rooms/" + enc(chatId) + "/tags/" + enc(tag);
        if (on) client.put(path, MatrixClient.JSON.createObjectNode().put("order", 0.5));
        else deleteIgnoringMissing(path);
    }

    private void putRoomAccountData(String chatId, String type, ObjectNode content) throws IOException {
        client.put(CLIENT + "/user/" + enc(userId) + "/rooms/" + enc(chatId) + "/account_data/" + enc(type), content);
    }

    /** Applies a chat field change locally, sends it, and reverts on failure. */
    private <T> CompletableFuture<Void> optimistic(String chatId, java.util.function.Function<Chat, T> getter,
                                                   java.util.function.BiConsumer<Chat, T> setter, T value, MatrixCore.IoAction call) {
        Chat c = core.chat(chatId);
        if (c == null) return CompletableFuture.failedFuture(new MatrixException(0, null, "Chat no encontrado"));
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
