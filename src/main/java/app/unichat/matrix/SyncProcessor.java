package app.unichat.matrix;

import app.unichat.model.Chat;
import app.unichat.model.Member;
import app.unichat.model.Message;
import app.unichat.model.MessageStatus;
import app.unichat.model.MessageType;
import app.unichat.service.PreferencesFile;
import app.unichat.support.I18n;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static app.unichat.matrix.SyncEvents.*;

/**
 * Turns Matrix /sync and /messages JSON into live {@link Chat} and {@link Message} objects.
 * No network, and not thread-safe: callers synchronize on the processor instance. Preferences aren't set here: what
 * arrives about them is kept raw on the room and {@link RoomPreferences} decides at the end of each response.
 */
final class SyncProcessor {
    static final String LOCAL_PREFIX = "local:";
    static String encryptedText() { return I18n.t("sync.encryptedMessage"); }
    static final String REMINDER_TYPE = "app.unichat.reminder";
    static final String TAG_ARCHIVED = "u.archived";
    static final String TAG_PINNED = "u.pinned";

    /** What changed in one processing step; the service turns it into listener callbacks. */
    static final class Result {
        boolean chatsChanged;
        final Set<String> messagesChanged = new LinkedHashSet<>();
        /** New incoming live messages (not outgoing, not system), candidates for notifications. */
        final List<Message> newMessages = new ArrayList<>();
        /** Per room, the time up to which I've read (my own receipts, e.g. read on the phone). */
        final Map<String, Instant> readByMe = new HashMap<>();
        /** Rooms we were newly invited to. */
        final List<String> invites = new ArrayList<>();
        /** Back-pagination only: false when the start of the room was reached. */
        boolean hasMore = true;
        /** What bridges said about messages (event id → status), for {@link SendStatuses}. */
        final Map<String, String> sendStatuses = new HashMap<>();
    }

    private final String me;
    private final RoomFields fields;
    private final SystemMessages systemMessages;
    private final Map<String, MatrixRoom> rooms = new LinkedHashMap<>();
    private final Map<String, String> directRoomUser = new HashMap<>();
    private ObjectNode directContent = MatrixClient.JSON.createObjectNode();
    private final RoomPreferences preferences = new RoomPreferences();

    SyncProcessor(String myUserId) {
        this.me = myUserId;
        this.fields = new RoomFields(me, directRoomUser, this::room);
        this.systemMessages = new SystemMessages(fields, this::room);
    }

    void refresh(MatrixRoom room) { fields.refresh(room); }

    Collection<MatrixRoom> rooms() { return rooms.values(); }
    MatrixRoom room(String id) { return id == null ? null : rooms.get(id); }
    MatrixRoom removeRoom(String id) { return rooms.remove(id); }
    ObjectNode directContentCopy() { return directContent.deepCopy(); }

    // ---------------------------------------------------------------- preferences

    void setPreferences(PreferencesFile file) { preferences.use(file); }

    /** The key to use for this room in the preferences file, always computed. */
    String prefKey(MatrixRoom room) { return preferences.keyOf(room); }

    /** Shows what the file says now, after a change from outside /sync. */
    void showPreferences(MatrixRoom room) { preferences.show(room); }

    /** Room → the mute rule to write: where the file and the server differ. */
    Map<String, Boolean> mutesToApply() { return mutesToApply(Instant.now()); }

    Map<String, Boolean> mutesToApply(Instant now) { return preferences.mutesToApply(rooms.values(), now); }

    void muteWriteStarted(String roomId) { preferences.writeStarted(roomId); }

    void muteWriteFinished(String roomId, boolean muted, boolean ok, Instant now) {
        preferences.writeFinished(roomId, muted, ok, now);
    }

    /** The cached token was rejected: a full sync follows, and what the cache said no longer counts. */
    void forgetServerValues() { preferences.forgetHeard(rooms.values()); }

    // ---------------------------------------------------------------- /sync

    Result process(JsonNode sync, boolean live) { return process(sync, live, true); }

    /** The sync saved on disk: everything shows at once, but preferences only count when the server answers. */
    Result replay(JsonNode cached) { return process(cached, false, false); }

    private Result process(JsonNode sync, boolean live, boolean serverAnswered) {
        Result r = new Result();
        for (JsonNode ev : sync.path("account_data").path("events")) applyGlobalAccountData(ev, r);

        JsonNode roomsNode = sync.path("rooms");
        for (Iterator<Map.Entry<String, JsonNode>> it = roomsNode.path("join").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            processJoined(e.getKey(), e.getValue(), live, r);
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = roomsNode.path("invite").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            processInvite(e.getKey(), e.getValue(), r);
        }
        for (Iterator<String> it = roomsNode.path("leave").fieldNames(); it.hasNext(); ) {
            if (rooms.remove(it.next()) != null) r.chatsChanged = true;
        }
        // Last: m.bridge state (the key), tags and the global rules are all in by now.
        if (preferences.settle(rooms.values(), serverAnswered)) r.chatsChanged = true;
        return r;
    }

    private void processJoined(String roomId, JsonNode j, boolean live, Result r) {
        MatrixRoom room = rooms.get(roomId);
        boolean isNew = room == null;
        if (isNew) {
            room = new MatrixRoom(roomId);
            rooms.put(roomId, room);
        }
        room.invite = false;
        r.chatsChanged = true;

        JsonNode summary = j.path("summary");
        if (summary.has("m.heroes")) room.heroes = strings(summary.get("m.heroes"));
        if (summary.has("m.joined_member_count")) room.joinedCount = summary.get("m.joined_member_count").asInt();
        if (summary.has("m.invited_member_count")) room.invitedCount = summary.get("m.invited_member_count").asInt();

        for (JsonNode ev : j.path("state").path("events")) applyState(room, ev);

        JsonNode tl = j.path("timeline");
        boolean limited = tl.path("limited").asBoolean(false);
        if (limited && !isNew) {
            resetTimeline(room);
            r.messagesChanged.add(roomId);
        }
        String prev = text(tl, "prev_batch");
        if (isNew || limited || room.prevBatch == null) room.prevBatch = prev;
        for (JsonNode ev : tl.path("events")) processEvent(room, ev, null, live, r);

        for (JsonNode ev : j.path("ephemeral").path("events")) applyEphemeral(room, ev, r);
        for (JsonNode ev : j.path("account_data").path("events")) applyRoomAccountData(room, ev);

        JsonNode un = j.path("unread_notifications");
        if (un.has("notification_count")) room.chat.setUnreadCount(un.get("notification_count").asInt());
        if (un.has("highlight_count")) room.chat.setMentionCount(un.get("highlight_count").asInt());

        fields.refresh(room);
    }

    private void processInvite(String roomId, JsonNode j, Result r) {
        MatrixRoom room = rooms.get(roomId);
        if (room != null && !room.invite) return;
        if (room == null) {
            room = new MatrixRoom(roomId);
            room.invite = true;
            room.invitedAt = Instant.now();
            rooms.put(roomId, room);
            r.invites.add(roomId);
        }
        for (JsonNode ev : j.path("invite_state").path("events")) applyState(room, ev);
        fields.refresh(room);
        r.chatsChanged = true;
    }

    /** Drops loaded history after a gap (limited sync), keeping unsent local echoes. */
    private void resetTimeline(MatrixRoom room) {
        List<Message> keep = room.timeline.stream().filter(m -> m.id().startsWith(LOCAL_PREFIX)).toList();
        room.timeline.clear();
        room.byId.clear();
        room.reactionEvents.clear();
        room.pending.clear();
        room.editTs.clear();
        for (Message m : keep) {
            room.timeline.add(m);
            room.byId.put(m.id(), m);
        }
    }

    // ---------------------------------------------------------------- /messages

    /** Prepends a /messages (dir=b) page to the room's timeline. */
    Result processHistory(String roomId, JsonNode resp) {
        Result r = new Result();
        MatrixRoom room = rooms.get(roomId);
        if (room == null) {
            r.hasMore = false;
            return r;
        }
        for (JsonNode ev : resp.path("state")) learnMember(room, ev);
        List<JsonNode> chunk = new ArrayList<>();
        resp.path("chunk").forEach(chunk::add);
        List<Message> older = new ArrayList<>();
        for (int i = chunk.size() - 1; i >= 0; i--) processEvent(room, chunk.get(i), older, false, r);
        room.timeline.addAll(0, older);
        room.prevBatch = text(resp, "end");
        r.hasMore = room.prevBatch != null && !chunk.isEmpty();
        r.messagesChanged.add(roomId);
        if (!older.isEmpty()) {
            fields.refresh(room);
            r.chatsChanged = true;
        }
        return r;
    }

    // ---------------------------------------------------------------- state

    private void applyState(MatrixRoom room, JsonNode ev) {
        String type = text(ev, "type");
        String key = text(ev, "state_key");
        if (type == null || key == null) return;
        JsonNode c = ev.path("content");
        switch (type) {
            case "m.room.name" -> room.name = text(c, "name");
            case "m.room.avatar" -> room.avatar = text(c, "url");
            case "m.room.topic" -> room.topic = text(c, "topic");
            case "m.room.canonical_alias" -> room.canonicalAlias = text(c, "alias");
            case "m.room.create" -> {
                long ts = ev.path("origin_server_ts").asLong(0);
                if (ts > 0) room.created = Instant.ofEpochMilli(ts);
                room.space = "m.space".equals(text(c, "type"));
                room.createdByBridge = isBot(text(ev, "sender"));
            }
            case "m.space.child" -> {
                if (c.isEmpty() || !c.has("via")) room.spaceChildOrder.remove(key);
                else room.spaceChildOrder.putIfAbsent(key, c.path("order").asText(""));
            }
            case "m.space.parent" -> {
                if (c.isEmpty() || !c.has("via")) room.spaceParents.remove(key);
                else room.spaceParents.add(key);
            }
            case "m.room.member" -> {
                String membership = text(c, "membership");
                if (membership != null) room.membership.put(key, membership);
                putMember(room, key, c);
                if (key.equals(me) && "invite".equals(membership)) room.inviter = text(ev, "sender");
                if (key.equals(me) && (c.path("is_direct").asBoolean(false)
                        || prevContent(ev).path("is_direct").asBoolean(false))) {
                    room.directInvite = true;
                }
            }
            case "com.beeper.room_features" -> room.roomFeatures = c;
            case "m.bridge", "uk.half-shot.bridge" -> {
                String proto = text(c.path("protocol"), "id");
                if (proto != null) room.bridgeProtocol = proto;
                String rt = text(c, "com.beeper.room_type");
                if (rt != null) room.bridgeRoomType = rt;
                String channel = text(c.path("channel"), "id");
                if (channel != null) room.bridgeChannelId = channel;
            }
            default -> { }
        }
    }

    private void putMember(MatrixRoom room, String userId, JsonNode content) {
        String name = text(content, "displayname");
        String avatar = text(content, "avatar_url");
        Member old = room.members.get(userId);
        if (old != null && name == null && avatar == null && !"join".equals(text(content, "membership"))) return;
        room.members.put(userId, new Member(userId, cleanName(name), avatar));
    }

    /** Fills in a profile fetched separately (lazy-loaded members); returns the rooms whose chat changed. */
    List<String> applyProfile(String userId, String name, String avatar) {
        List<String> changed = new ArrayList<>();
        for (MatrixRoom room : rooms.values()) {
            if (!room.membership.containsKey(userId) && !room.heroes.contains(userId)) continue;
            Member old = room.members.get(userId);
            if (old != null && old.name() != null && !old.name().isBlank()) continue;
            room.members.put(userId, new Member(userId, cleanName(name), avatar != null ? avatar : old == null ? null : old.avatarUrl()));
            fields.refresh(room);
            changed.add(room.id);
        }
        return changed;
    }

    /** Users shown in a chat title or avatar without a known display name (to fetch their profile). */
    Set<String> unnamedUsers() {
        Set<String> out = new LinkedHashSet<>();
        for (MatrixRoom room : rooms.values()) {
            if (room.invite) continue;
            for (String u : fields.otherUsers(room)) {
                Member m = room.members.get(u);
                if ((m == null || m.name() == null || m.name().isBlank()) && !isBot(u)) out.add(u);
            }
        }
        return out;
    }

    /** Older member events only fill in unknown profiles; they never override current state. */
    private void learnMember(MatrixRoom room, JsonNode ev) {
        if (!"m.room.member".equals(text(ev, "type"))) return;
        String key = text(ev, "state_key");
        if (key == null || room.members.containsKey(key)) return;
        putMember(room, key, ev.path("content"));
        String membership = text(ev.path("content"), "membership");
        if (membership != null) room.membership.putIfAbsent(key, membership);
    }

    // ---------------------------------------------------------------- timeline

    /** {@code older} is null for live/sync events (appended), or the batch being prepended. */
    private void processEvent(MatrixRoom room, JsonNode ev, List<Message> older, boolean live, Result r) {
        String type = text(ev, "type");
        String id = text(ev, "event_id");
        String sender = text(ev, "sender");
        if (type == null || id == null || sender == null) return;
        if (ev.has("state_key")) {
            if (older == null) applyState(room, ev); else learnMember(room, ev);
        }
        if (room.byId.containsKey(id) || room.reactionEvents.containsKey(id)) return;

        JsonNode content = ev.path("content");
        boolean redacted = ev.path("unsigned").has("redacted_because");
        switch (type) {
            case "m.room.message", "m.sticker" -> {
                JsonNode rel = content.path("m.relates_to");
                if ("m.replace".equals(text(rel, "rel_type"))) {
                    applyEdit(room, ev, r);
                    return;
                }
                Message m = newMessage(room, ev);
                if (redacted || content.isEmpty()) {
                    m.setDeleted(true);
                } else {
                    String replyTo = text(rel.path("m.in_reply_to"), "event_id");
                    String body = text(content, "body");
                    if (replyTo != null) {
                        m.setReplyToId(replyTo);
                        body = stripReplyFallback(body);
                    }
                    String msgtype = type.equals("m.sticker") ? "m.sticker" : text(content, "msgtype");
                    applyContent(m, msgtype, body, content);
                }
                addMessage(room, m, ev, older, live, r);
            }
            case "m.room.encrypted" -> {
                Message m = newMessage(room, ev);
                m.setType(MessageType.SYSTEM);
                m.setBody(encryptedText());
                if (redacted) m.setDeleted(true);
                addMessage(room, m, ev, older, live, r);
            }
            case "m.reaction" -> {
                if (!redacted) applyReaction(room, ev, r);
            }
            case "m.room.redaction" -> applyRedaction(room, ev, r);
            case SEND_STATUS -> applySendStatus(room, ev, r);
            default -> {
                if (!ev.has("state_key") || redacted) return;
                String text = systemMessages.of(room, ev);
                if (text == null) return;
                Message m = newMessage(room, ev);
                m.setType(MessageType.SYSTEM);
                m.setBody(text);
                addMessage(room, m, ev, older, live, r);
            }
        }
    }

    private Message newMessage(MatrixRoom room, JsonNode ev) {
        String sender = text(ev, "sender");
        long ts = ev.path("origin_server_ts").asLong(0);
        return new Message(text(ev, "event_id"), room.id, sender, fields.nameOf(room, sender), me.equals(sender),
                Instant.ofEpochMilli(ts));
    }

    private void addMessage(MatrixRoom room, Message m, JsonNode ev, List<Message> older, boolean live, Result r) {
        r.messagesChanged.add(room.id);
        String txn = text(ev.path("unsigned"), "transaction_id");
        if (older == null && txn != null) {
            Message local = room.byId.remove(LOCAL_PREFIX + txn);
            if (local != null) {
                int idx = room.timeline.indexOf(local);
                if (idx >= 0) room.timeline.set(idx, m); else room.timeline.add(m);
                room.byId.put(m.id(), m);
                applyPending(room, m.id(), r);
                return;
            }
        }
        room.byId.put(m.id(), m);
        if (older != null) older.add(m); else room.timeline.add(m);
        applyPending(room, m.id(), r);
        if (live && older == null && !m.outgoing() && m.type() != MessageType.SYSTEM) r.newMessages.add(m);
    }

    private void applyPending(MatrixRoom room, String targetId, Result r) {
        List<JsonNode> list = room.pending.remove(targetId);
        if (list == null) return;
        for (JsonNode ev : list) {
            switch (String.valueOf(text(ev, "type"))) {
                case "m.reaction" -> applyReaction(room, ev, r);
                case SEND_STATUS -> applySendStatus(room, ev, r);
                default -> applyEdit(room, ev, r);
            }
        }
    }

    /** What a bridge says about a message it was given (matrix.message_status_events). */
    static final String SEND_STATUS = "com.beeper.message_send_status";

    /**
     * A message of ours the bridge couldn't pass on to the network shows as failed (the bridge's notice says why); a later
     * success, after a retry, undoes it. Nothing else changes: delivery and reading come from receipts.
     */
    private void applySendStatus(MatrixRoom room, JsonNode ev, Result r) {
        JsonNode content = ev.path("content");
        String target = text(content.path("m.relates_to"), "event_id");
        if (target == null) return;
        String status = String.valueOf(text(content, "status"));
        r.sendStatuses.put(target, status);
        Message t = room.byId.get(target);
        if (t == null) {
            addPending(room, target, ev);
            return;
        }
        if (!t.outgoing()) return;
        MessageStatus next = status.startsWith("FAIL") ? MessageStatus.FAILED
                : "SUCCESS".equals(status) && t.status() == MessageStatus.FAILED ? MessageStatus.SENT : null;
        if (next == null || next == t.status()) return;
        t.setStatus(next);
        r.messagesChanged.add(room.id);
    }

    private void addPending(MatrixRoom room, String targetId, JsonNode ev) {
        room.pending.computeIfAbsent(targetId, k -> new ArrayList<>()).add(ev);
    }

    private void applyEdit(MatrixRoom room, JsonNode ev, Result r) {
        if (ev.path("unsigned").has("redacted_because")) return;
        JsonNode content = ev.path("content");
        String target = text(content.path("m.relates_to"), "event_id");
        if (target == null) return;
        Message t = room.byId.get(target);
        if (t == null) {
            addPending(room, target, ev);
            return;
        }
        if (!t.senderId().equals(text(ev, "sender"))) return;
        long ts = ev.path("origin_server_ts").asLong(0);
        Long prev = room.editTs.get(target);
        if (prev != null && prev > ts) return;
        room.editTs.put(target, ts);
        JsonNode nc = content.has("m.new_content") ? content.get("m.new_content") : content;
        String body = text(nc, "body");
        if (body == null) return;
        if (t.isMedia() && text(nc, "url") == null) {
            t.setBody(body.equals(t.fileName()) ? "" : body);
        } else {
            String msgtype = text(nc, "msgtype");
            if (msgtype == null) msgtype = "m.text";
            applyContent(t, msgtype, body, nc);
        }
        t.setEdited(true);
        r.messagesChanged.add(room.id);
        r.chatsChanged = true;
    }

    private void applyReaction(MatrixRoom room, JsonNode ev, Result r) {
        JsonNode rel = ev.path("content").path("m.relates_to");
        String target = text(rel, "event_id");
        String key = text(rel, "key");
        String sender = text(ev, "sender");
        String id = text(ev, "event_id");
        if (target == null || key == null || !"m.annotation".equals(text(rel, "rel_type"))) return;
        room.reactionEvents.put(id, new MatrixRoom.Reaction(target, key, sender));
        Message t = room.byId.get(target);
        if (t == null) {
            addPending(room, target, ev);
            return;
        }
        String shortcode = text(ev.path("content"), "com.beeper.reaction.shortcode");
        if (shortcode != null) t.setReactionName(key, shortcode);
        t.addReaction(key, sender);
        r.messagesChanged.add(room.id);
    }

    private void applyRedaction(MatrixRoom room, JsonNode ev, Result r) {
        String target = text(ev.path("content"), "redacts");
        if (target == null) target = text(ev, "redacts");
        if (target != null) redact(room, target, r);
    }

    /** Applies a redaction of {@code targetId}: removes a reaction or marks a message deleted. */
    void redact(MatrixRoom room, String targetId, Result r) {
        MatrixRoom.Reaction re = room.reactionEvents.remove(targetId);
        if (re != null) {
            Message t = room.byId.get(re.targetId());
            boolean stillReacted = room.reactionEvents.values().stream().anyMatch(o -> o.equals(re));
            if (t != null && !stillReacted) {
                t.removeReaction(re.key(), re.sender());
                r.messagesChanged.add(room.id);
            }
            return;
        }
        Message m = room.byId.get(targetId);
        if (m != null && !m.deleted()) {
            m.markDeleted();
            r.messagesChanged.add(room.id);
            r.chatsChanged = true;
            fields.refresh(room);
        }
    }

    // ---------------------------------------------------------------- ephemeral & account data

    private void applyEphemeral(MatrixRoom room, JsonNode ev, Result r) {
        String type = text(ev, "type");
        JsonNode c = ev.path("content");
        if ("m.typing".equals(type)) {
            Set<String> typing = new java.util.LinkedHashSet<>();
            for (JsonNode u : c.path("user_ids")) {
                String uid = u.asText();
                if (!uid.equals(me)) typing.add(fields.nameOf(room, uid));
            }
            room.chat.setTyping(typing);
            r.chatsChanged = true;
        } else if ("m.receipt".equals(type)) {
            for (Iterator<Map.Entry<String, JsonNode>> it = c.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                boolean byOther = false;
                for (Iterator<String> users = e.getValue().path("m.read").fieldNames(); users.hasNext(); ) {
                    if (!users.next().equals(me)) byOther = true;
                }
                for (String kind : new String[]{"m.read", "m.read.private"}) {
                    JsonNode mine = e.getValue().path(kind).path(me);
                    if (mine.isMissingNode()) continue;
                    // Read up to that message (its time); a message not loaded here: when the receipt was sent.
                    Message read = room.byId.get(e.getKey());
                    Instant t = read != null ? read.timestamp() : Instant.ofEpochMilli(mine.path("ts").asLong(0));
                    r.readByMe.merge(room.id, t, (a, b) -> a.isAfter(b) ? a : b);
                }
                if (byOther && markReadUpTo(room, e.getKey())) r.messagesChanged.add(room.id);
            }
        }
    }

    /** Marks outgoing messages up to {@code eventId} as READ; returns true if anything changed. */
    private boolean markReadUpTo(MatrixRoom room, String eventId) {
        Message target = room.byId.get(eventId);
        int idx = target == null ? -1 : room.timeline.indexOf(target);
        boolean changed = false;
        for (int i = 0; i <= idx; i++) {
            Message m = room.timeline.get(i);
            if (m.outgoing() && (m.status() == MessageStatus.SENT || m.status() == MessageStatus.DELIVERED)) {
                m.setStatus(MessageStatus.READ);
                changed = true;
            }
        }
        return changed;
    }

    private void applyRoomAccountData(MatrixRoom room, JsonNode ev) {
        String type = text(ev, "type");
        JsonNode c = ev.path("content");
        Chat chat = room.chat;
        if (type == null) return;
        switch (type) {
            case "m.tag" -> {
                room.serverTags = new HashSet<>();
                c.path("tags").fieldNames().forEachRemaining(room.serverTags::add);
            }
            case "m.marked_unread" -> {
                room.markedUnreadStable = true;
                chat.setMarkedUnread(c.path("unread").asBoolean(false));
            }
            case "com.famedly.marked_unread" -> {
                if (!room.markedUnreadStable) chat.setMarkedUnread(c.path("unread").asBoolean(false));
            }
            case REMINDER_TYPE -> {
                long ts = c.path("ts").asLong(0);
                room.serverReminder = ts > 0 ? Instant.ofEpochMilli(ts) : null;
                room.reminderKnown = true;
            }
            default -> { }
        }
    }

    private void applyGlobalAccountData(JsonNode ev, Result r) {
        String type = text(ev, "type");
        JsonNode c = ev.path("content");
        if ("m.direct".equals(type) && c.isObject()) {
            directContent = (ObjectNode) c.deepCopy();
            directRoomUser.clear();
            for (Iterator<Map.Entry<String, JsonNode>> it = c.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                for (JsonNode roomId : e.getValue()) directRoomUser.put(roomId.asText(), e.getKey());
            }
            rooms.values().forEach(this::refresh);
            r.chatsChanged = true;
        } else if ("m.push_rules".equals(type)) {
            preferences.hearRules(mutedRoomsFrom(c));
            r.chatsChanged = true;
        }
    }

    // ---------------------------------------------------------------- local echo & helpers for the service

    /** Appends an outgoing message shown while it is being sent. */
    Message addLocalEcho(String roomId, String txnId, Consumer<Message> init) {
        MatrixRoom room = rooms.get(roomId);
        Message m = new Message(LOCAL_PREFIX + txnId, roomId, me, room == null ? null : fields.nameOf(room, me), true, Instant.now());
        m.setStatus(MessageStatus.SENDING);
        init.accept(m);
        if (room != null) {
            room.timeline.add(m);
            room.byId.put(m.id(), m);
            room.chat.applyLastMessage(m);
        }
        return m;
    }

    /** The server accepted a local echo: swap it for a message with the real event id (unless sync already did). */
    void confirmLocalEcho(String roomId, String txnId, String eventId) {
        MatrixRoom room = rooms.get(roomId);
        if (room == null) return;
        Message local = room.byId.remove(LOCAL_PREFIX + txnId);
        if (local == null) return;
        int idx = room.timeline.indexOf(local);
        if (eventId == null || room.byId.containsKey(eventId)) {
            if (idx >= 0) room.timeline.remove(idx);
            return;
        }
        Message real = local.withId(eventId);
        real.setStatus(MessageStatus.SENT);
        if (idx >= 0) room.timeline.set(idx, real); else room.timeline.add(real);
        room.byId.put(eventId, real);
        Result ignored = new Result();
        applyPending(room, eventId, ignored);
        fields.refresh(room);
    }

    void failLocalEcho(String roomId, String txnId) {
        MatrixRoom room = rooms.get(roomId);
        Message local = room == null ? null : room.byId.get(LOCAL_PREFIX + txnId);
        if (local != null) local.setStatus(MessageStatus.FAILED);
    }

    void removeLocal(String roomId, String messageId) {
        MatrixRoom room = rooms.get(roomId);
        if (room == null) return;
        Message m = room.byId.remove(messageId);
        if (m != null) {
            room.timeline.remove(m);
            fields.refresh(room);
        }
    }

    /** Records a reaction we just sent so the sync echo is deduplicated and it can be removed later. */
    void registerOwnReaction(String roomId, String eventId, String targetId, String key) {
        MatrixRoom room = rooms.get(roomId);
        if (room == null || eventId == null) return;
        room.reactionEvents.put(eventId, new MatrixRoom.Reaction(targetId, key, me));
        Message t = room.byId.get(targetId);
        if (t != null) t.addReaction(key, me);
    }

    /** Event id of my reaction {@code key} on {@code targetId}, or null. */
    String ownReactionEvent(String roomId, String targetId, String key) {
        MatrixRoom room = rooms.get(roomId);
        if (room == null) return null;
        MatrixRoom.Reaction wanted = new MatrixRoom.Reaction(targetId, key, me);
        for (Map.Entry<String, MatrixRoom.Reaction> e : room.reactionEvents.entrySet()) {
            if (e.getValue().equals(wanted)) return e.getKey();
        }
        return null;
    }

    /** Newest server-side event id in the loaded timeline. */
    String lastEventId(String roomId) {
        MatrixRoom room = rooms.get(roomId);
        if (room == null) return null;
        for (int i = room.timeline.size() - 1; i >= 0; i--) {
            String id = room.timeline.get(i).id();
            if (!id.startsWith(LOCAL_PREFIX)) return id;
        }
        return null;
    }

}
