package app.unichat.matrix;

import app.unichat.model.Chat;
import app.unichat.model.Member;
import app.unichat.model.Message;
import app.unichat.model.MessageStatus;
import app.unichat.model.MessageType;
import app.unichat.model.Network;
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
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Turns Matrix /sync and /messages JSON into live {@link Chat} and {@link Message} objects.
 * Pure (no I/O) and not thread-safe: callers synchronize on the processor instance.
 */
final class SyncProcessor {
    static final String LOCAL_PREFIX = "local:";
    static final String ENCRYPTED_TEXT = "🔒 Mensaje cifrado no compatible";
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
    }

    private final String me;
    private final Map<String, MatrixRoom> rooms = new LinkedHashMap<>();
    private final Map<String, String> directRoomUser = new HashMap<>();
    private ObjectNode directContent = MatrixClient.JSON.createObjectNode();
    private Set<String> mutedRooms = new HashSet<>();

    SyncProcessor(String myUserId) {
        this.me = myUserId;
    }

    String myUserId() { return me; }
    Collection<MatrixRoom> rooms() { return rooms.values(); }
    MatrixRoom room(String id) { return id == null ? null : rooms.get(id); }
    MatrixRoom removeRoom(String id) { return rooms.remove(id); }
    ObjectNode directContentCopy() { return directContent.deepCopy(); }

    void setMutedLocal(String roomId, boolean muted) {
        if (muted) mutedRooms.add(roomId); else mutedRooms.remove(roomId);
        MatrixRoom r = rooms.get(roomId);
        if (r != null) r.chat.setMuted(muted);
    }

    // ---------------------------------------------------------------- /sync

    Result process(JsonNode sync, boolean live) {
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
        return r;
    }

    private void processJoined(String roomId, JsonNode j, boolean live, Result r) {
        MatrixRoom room = rooms.get(roomId);
        boolean isNew = room == null;
        if (isNew) {
            room = new MatrixRoom(roomId);
            rooms.put(roomId, room);
            room.chat.setMuted(mutedRooms.contains(roomId));
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

        refresh(room);
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
        refresh(room);
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
            refresh(room);
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
                String child = text(ev, "state_key");
                if (child == null) break;
                if (c.isEmpty() || !c.has("via")) room.spaceChildOrder.remove(child);
                else room.spaceChildOrder.putIfAbsent(child, c.path("order").asText(""));
            }
            case "m.space.parent" -> {
                String parent = text(ev, "state_key");
                if (parent == null) break;
                if (c.isEmpty() || !c.has("via")) room.spaceParents.remove(parent);
                else room.spaceParents.add(parent);
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

    /** Bridges append the network to puppet names ("Ana (WA)"); the app shows a network badge instead. */
    private static final java.util.regex.Pattern NETWORK_SUFFIX = java.util.regex.Pattern.compile(
            "\\s*\\((WA|WhatsApp|Telegram|Signal|Instagram|Facebook|Messenger|Discord|Slack|Google Messages|GMessages|SMS|Twitter|X|LinkedIn)\\)$");

    static String cleanName(String name) {
        if (name == null) return null;
        String n = NETWORK_SUFFIX.matcher(name).replaceFirst("");
        return n.isBlank() ? name : n;
    }

    /** Fills in a profile fetched separately (lazy-loaded members); returns the rooms whose chat changed. */
    List<String> applyProfile(String userId, String name, String avatar) {
        List<String> changed = new ArrayList<>();
        for (MatrixRoom room : rooms.values()) {
            if (!room.membership.containsKey(userId) && !room.heroes.contains(userId)) continue;
            Member old = room.members.get(userId);
            if (old != null && old.name() != null && !old.name().isBlank()) continue;
            room.members.put(userId, new Member(userId, cleanName(name), avatar != null ? avatar : old == null ? null : old.avatarUrl()));
            refresh(room);
            changed.add(room.id);
        }
        return changed;
    }

    /** Users shown in a chat title or avatar without a known display name (to fetch their profile). */
    Set<String> unnamedUsers() {
        Set<String> out = new LinkedHashSet<>();
        for (MatrixRoom room : rooms.values()) {
            if (room.invite) continue;
            for (String u : otherUsers(room)) {
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
                m.setBody(ENCRYPTED_TEXT);
                if (redacted) m.setDeleted(true);
                addMessage(room, m, ev, older, live, r);
            }
            case "m.reaction" -> {
                if (!redacted) applyReaction(room, ev, r);
            }
            case "m.room.redaction" -> applyRedaction(room, ev, r);
            default -> {
                if (!ev.has("state_key") || redacted) return;
                String text = systemText(room, ev);
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
        return new Message(text(ev, "event_id"), room.id, sender, nameOf(room, sender), me.equals(sender),
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
            if ("m.reaction".equals(text(ev, "type"))) applyReaction(room, ev, r); else applyEdit(room, ev, r);
        }
    }

    private void addPending(MatrixRoom room, String targetId, JsonNode ev) {
        room.pending.computeIfAbsent(targetId, k -> new ArrayList<>()).add(ev);
    }

    /** Sets type, body, media fields from a message content (also used for m.new_content). */
    static void applyContent(Message m, String msgtype, String body, JsonNode content) {
        MessageType t = switch (msgtype == null ? "" : msgtype) {
            case "m.emote" -> MessageType.EMOTE;
            case "m.notice" -> MessageType.NOTICE;
            case "m.image" -> MessageType.IMAGE;
            case "m.video" -> MessageType.VIDEO;
            case "m.audio" -> MessageType.AUDIO;
            case "m.file" -> MessageType.FILE;
            case "m.sticker" -> MessageType.STICKER;
            default -> MessageType.TEXT;
        };
        String b = body == null ? "" : body;
        m.setType(t);
        if (!m.isMedia()) {
            m.setBody(b);
            return;
        }
        String url = text(content, "url");
        if (url == null && content.has("file")) {
            m.setType(MessageType.TEXT);
            m.setBody("🔒 Archivo cifrado no compatible");
            return;
        }
        m.setMediaUrl(url);
        String filename = text(content, "filename");
        if (filename != null && !filename.equals(b)) {
            m.setFileName(filename);
            m.setBody(b);
        } else {
            m.setFileName(b.isEmpty() ? null : b);
            m.setBody("");
        }
        JsonNode info = content.path("info");
        if (info.isObject()) m.setMediaInfo(info);
        m.setMimeType(text(info, "mimetype"));
        m.setSize(info.path("w").asInt(0), info.path("h").asInt(0));
        m.setFileSize(info.path("size").asLong(0));
    }

    /** Removes the "> quoted" reply fallback that precedes the real body. */
    static String stripReplyFallback(String body) {
        if (body == null || !body.startsWith(">")) return body;
        String[] lines = body.split("\n", -1);
        int i = 0;
        while (i < lines.length && lines[i].startsWith(">")) i++;
        if (i < lines.length && lines[i].isEmpty()) i++;
        if (i >= lines.length) return body;
        return String.join("\n", java.util.Arrays.copyOfRange(lines, i, lines.length));
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
        Set<String> users = t.reactions().get(key);
        if (users == null || !users.contains(sender)) t.toggleReaction(key, sender);
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
                Set<String> users = t.reactions().get(re.key());
                if (users != null && users.contains(re.sender())) t.toggleReaction(re.key(), re.sender());
                r.messagesChanged.add(room.id);
            }
            return;
        }
        Message m = room.byId.get(targetId);
        if (m != null && !m.deleted()) {
            m.setDeleted(true);
            m.setBody("");
            m.reactions().clear();
            r.messagesChanged.add(room.id);
            r.chatsChanged = true;
            refresh(room);
        }
    }

    // ---------------------------------------------------------------- ephemeral & account data

    private void applyEphemeral(MatrixRoom room, JsonNode ev, Result r) {
        String type = text(ev, "type");
        JsonNode c = ev.path("content");
        if ("m.typing".equals(type)) {
            room.chat.typing().clear();
            for (JsonNode u : c.path("user_ids")) {
                String uid = u.asText();
                if (!uid.equals(me)) room.chat.typing().add(nameOf(room, uid));
            }
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
                JsonNode tags = c.path("tags");
                chat.setFavorite(tags.has("m.favourite"));
                chat.setLowPriority(tags.has("m.lowpriority"));
                chat.setArchived(tags.has(TAG_ARCHIVED));
                chat.setPinned(tags.has(TAG_PINNED));
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
                chat.setReminderAt(ts > 0 ? Instant.ofEpochMilli(ts) : null);
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
            mutedRooms = mutedRoomsFrom(c);
            for (MatrixRoom room : rooms.values()) room.chat.setMuted(mutedRooms.contains(room.id));
            r.chatsChanged = true;
        }
    }

    /** Rooms with a room (or per-room override) push rule that does not notify. */
    static Set<String> mutedRoomsFrom(JsonNode pushRules) {
        Set<String> muted = new HashSet<>();
        JsonNode global = pushRules.path("global");
        for (JsonNode rule : global.path("room")) {
            if (rule.path("enabled").asBoolean(true) && isSilent(rule.path("actions"))) {
                String id = text(rule, "rule_id");
                if (id != null) muted.add(id);
            }
        }
        for (JsonNode rule : global.path("override")) {
            String id = text(rule, "rule_id");
            if (id == null || !id.startsWith("!") || !rule.path("enabled").asBoolean(true)) continue;
            if (isSilent(rule.path("actions"))) muted.add(id);
        }
        return muted;
    }

    private static boolean isSilent(JsonNode actions) {
        if (!actions.isArray() || actions.isEmpty()) return true;
        for (JsonNode a : actions) if ("dont_notify".equals(a.asText())) return true;
        return false;
    }

    // ---------------------------------------------------------------- derived chat fields

    /** Recomputes title, avatar, network, group, members and preview of a room's chat. */
    void refresh(MatrixRoom room) {
        Chat c = room.chat;
        boolean botRoom = isBridgeBotRoom(room);
        c.setBridgeBot(botRoom);
        c.setNetwork(detectNetwork(room, botRoom));
        c.setFeatures(room.roomFeatures != null ? app.unichat.model.RoomFeatures.parse(room.roomFeatures)
                : c.network() == Network.DISCORD ? app.unichat.model.RoomFeatures.discord() : app.unichat.model.RoomFeatures.UNKNOWN);
        c.setSpace(room.space);
        // The bridge's own folder: bridgev2 marks it with a "__" channel; mautrix-discord's folders ("Your Discord
        // bridged chats", "direct messages") are made by its bot with no channel at all.
        c.setPersonalSpace(room.space && (room.bridgeChannelId != null ? room.bridgeChannelId.startsWith("__") : room.createdByBridge));
        c.setSpaceChildren(orderedChildren(room));
        c.setSpaceParents(room.spaceParents);
        boolean group = !botRoom && !room.space && isGroup(room);
        c.setGroup(group);
        c.setTopic(room.topic);

        String counterpart = group ? null : counterpart(room);
        String title = cleanName(blankToNull(room.name));
        if (title == null && counterpart != null) title = nameOf(room, counterpart);
        if (title == null) title = heroesTitle(room);
        if (title == null) title = blankToNull(room.canonicalAlias);
        if (title == null) title = room.invite ? "Invitación" : "Chat vacío";
        c.setTitle(title);

        String avatar = room.avatar;
        if (avatar == null && counterpart != null) {
            Member m = room.members.get(counterpart);
            if (m != null) avatar = m.avatarUrl();
        }
        c.setAvatarUrl(avatar);

        List<Member> members = new ArrayList<>();
        for (Map.Entry<String, String> e : room.membership.entrySet()) {
            if (!"join".equals(e.getValue()) && !"invite".equals(e.getValue())) continue;
            Member m = room.members.get(e.getKey());
            members.add(m != null ? m : new Member(e.getKey(), null, null));
        }
        if (!members.equals(c.members())) {
            c.members().clear();
            c.members().addAll(members);
        }

        Message last = null;
        for (int i = room.timeline.size() - 1; i >= 0; i--) {
            Message m = room.timeline.get(i);
            if (last == null) last = m;
            if (m.type() != MessageType.SYSTEM) {
                last = m;
                break;
            }
        }
        if (last != null) {
            c.applyLastMessage(last);
        } else if (room.invite) {
            String inviter = room.inviter;
            c.setLastActivity(room.invitedAt);
            c.setLastPreview(inviter != null ? "Invitación de " + nameOf(room, inviter) : "Te invitaron a este chat");
        } else {
            // Joined but no messages loaded yet: no stale invite text, and don't jump to the top of the
            // inbox just because the bridge created the room now (bridges create hundreds after a login).
            c.setLastPreview("");
            c.setLastSenderName(null);
            c.setLastActivity(Instant.EPOCH);
        }
    }

    /** Children with an {@code order} first (lexicographic, as the spec says), then the rest in arrival order. */
    private static List<String> orderedChildren(MatrixRoom room) {
        List<String> ordered = new ArrayList<>(), rest = new ArrayList<>();
        room.spaceChildOrder.forEach((id, order) -> (order.isEmpty() ? rest : ordered).add(id));
        ordered.sort(java.util.Comparator.comparing(room.spaceChildOrder::get));
        ordered.addAll(rest);
        return ordered;
    }

    private boolean isBridgeBotRoom(MatrixRoom room) {
        if (room.space) return false;
        if (room.bridgeProtocol != null) return false;
        List<String> others = otherUsers(room);
        if (others.isEmpty()) return false;
        for (String u : others) if (!isBot(u)) return false;
        int count = memberCount(room);
        return count <= 2;
    }

    private Network detectNetwork(MatrixRoom room, boolean botRoom) {
        Network n = Network.fromProtocolId(room.bridgeProtocol);
        if (n != null) return n;
        for (String u : otherUsers(room)) {
            n = Network.fromUserId(u);
            if (n != null) return n;
        }
        for (String u : room.membership.keySet()) {
            n = Network.fromUserId(u);
            if (n != null) return n;
        }
        return Network.MATRIX;
    }

    private boolean isGroup(MatrixRoom room) {
        if (room.bridgeRoomType != null) return !"dm".equals(room.bridgeRoomType);
        if (directRoomUser.containsKey(room.id) || room.directInvite) return false;
        // A native Matrix room someone gave a name to is a group even with two members;
        // bridged portals are excluded because bridges name their DM portals too.
        if (room.name != null && !room.name.isBlank() && room.bridgeProtocol == null
                && room.membership.keySet().stream().noneMatch(u -> Network.fromUserId(u) != null)) return true;
        int count = memberCount(room);
        int bots = 0;
        for (Map.Entry<String, String> e : room.membership.entrySet()) {
            if (isBot(e.getKey()) && ("join".equals(e.getValue()) || "invite".equals(e.getValue()))) bots++;
        }
        return count - bots > 2;
    }

    private int memberCount(MatrixRoom room) {
        if (room.joinedCount >= 0) return room.joinedCount + Math.max(0, room.invitedCount);
        int n = 0;
        for (String m : room.membership.values()) if ("join".equals(m) || "invite".equals(m)) n++;
        return Math.max(n, room.heroes.size() + 1);
    }

    /** Active members (falling back to heroes) other than me. */
    private List<String> otherUsers(MatrixRoom room) {
        Set<String> out = new LinkedHashSet<>();
        for (String u : room.membership.keySet()) if (!u.equals(me) && room.isActiveMember(u)) out.add(u);
        for (String u : room.heroes) if (!u.equals(me) && !"leave".equals(room.membership.get(u))
                && !"ban".equals(room.membership.get(u))) out.add(u);
        return new ArrayList<>(out);
    }

    private String counterpart(MatrixRoom room) {
        String direct = directRoomUser.get(room.id);
        if (direct != null && !direct.equals(me)) return direct;
        List<String> others = otherUsers(room);
        for (String u : others) if (!isBot(u)) return u;
        return others.isEmpty() ? null : others.get(0);
    }

    private String heroesTitle(MatrixRoom room) {
        List<String> names = new ArrayList<>();
        for (String u : otherUsers(room)) {
            if (isBot(u)) continue;
            names.add(nameOf(room, u));
            if (names.size() == 4) break;
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }

    // ---------------------------------------------------------------- system messages

    private String systemText(MatrixRoom room, JsonNode ev) {
        String type = text(ev, "type");
        String sender = text(ev, "sender");
        if (isBot(sender) || !isGroup(room)) return null;
        String sn = nameOf(room, sender);
        JsonNode c = ev.path("content");
        switch (type) {
            case "m.room.member" -> {
                String target = text(ev, "state_key");
                if (target == null || isBot(target)) return null;
                JsonNode prev = prevContent(ev);
                String mem = text(c, "membership");
                String prevMem = text(prev, "membership");
                String dn = text(c, "displayname");
                String tn = dn != null ? dn : nameOf(room, target);
                if (mem == null) return null;
                return switch (mem) {
                    case "join" -> {
                        if (!"join".equals(prevMem)) yield tn + " se unió";
                        String pn = text(prev, "displayname");
                        yield pn != null && dn != null && !pn.equals(dn) ? pn + " cambió su nombre a " + dn : null;
                    }
                    case "invite" -> sn + " invitó a " + tn;
                    case "leave" -> target.equals(sender)
                            ? ("invite".equals(prevMem) ? tn + " rechazó la invitación" : tn + " salió")
                            : sn + " eliminó a " + tn;
                    case "ban" -> sn + " bloqueó a " + tn;
                    default -> null;
                };
            }
            case "m.room.name" -> {
                String n = blankToNull(text(c, "name"));
                return n == null ? sn + " quitó el nombre del grupo" : sn + " cambió el nombre del grupo a " + n;
            }
            case "m.room.topic" -> { return sn + " cambió la descripción del grupo"; }
            case "m.room.avatar" -> { return sn + " cambió la foto del grupo"; }
            default -> { return null; }
        }
    }

    // ---------------------------------------------------------------- local echo & helpers for the service

    /** Appends an outgoing message shown while it is being sent. */
    Message addLocalEcho(String roomId, String txnId, Consumer<Message> init) {
        MatrixRoom room = rooms.get(roomId);
        Message m = new Message(LOCAL_PREFIX + txnId, roomId, me, room == null ? null : nameOf(room, me), true, Instant.now());
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
        Message real = copy(local, eventId);
        real.setStatus(MessageStatus.SENT);
        if (idx >= 0) room.timeline.set(idx, real); else room.timeline.add(real);
        room.byId.put(eventId, real);
        Result ignored = new Result();
        applyPending(room, eventId, ignored);
        refresh(room);
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
            refresh(room);
        }
    }

    /** Records a reaction we just sent so the sync echo is deduplicated and it can be removed later. */
    void registerOwnReaction(String roomId, String eventId, String targetId, String key) {
        MatrixRoom room = rooms.get(roomId);
        if (room == null || eventId == null) return;
        room.reactionEvents.put(eventId, new MatrixRoom.Reaction(targetId, key, me));
        Message t = room.byId.get(targetId);
        if (t != null) {
            Set<String> users = t.reactions().get(key);
            if (users == null || !users.contains(me)) t.toggleReaction(key, me);
        }
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

    static Message copy(Message src, String newId) {
        Message m = new Message(newId, src.chatId(), src.senderId(), src.senderName(), src.outgoing(), src.timestamp());
        m.setType(src.type());
        m.setBody(src.body());
        m.setMediaUrl(src.mediaUrl());
        m.setFileName(src.fileName());
        m.setFileSize(src.fileSize());
        m.setSize(src.width(), src.height());
        m.setReplyToId(src.replyToId());
        m.setEdited(src.edited());
        m.setDeleted(src.deleted());
        m.setStatus(src.status());
        src.reactions().forEach((k, v) -> m.reactions().put(k, new LinkedHashSet<>(v)));
        return m;
    }

    String nameOf(MatrixRoom room, String userId) {
        Member m = room.members.get(userId);
        if (m != null && m.name() != null && !m.name().isBlank()) return m.name();
        return fallbackName(userId);
    }

    /** Name for a user whose profile isn't known yet: "+57300…" for phone puppets, else "Contacto de WhatsApp". */
    static String fallbackName(String userId) {
        String local = localpart(userId);
        Network n = Network.fromUserId(userId);
        if (n == null || n.puppetPrefix() == null || !local.startsWith(n.puppetPrefix())) return local;
        String rest = local.substring(n.puppetPrefix().length());
        if (rest.matches("\\d{6,15}")) return "+" + rest;
        return "Contacto de " + n.displayName();
    }

    static String localpart(String userId) {
        if (userId == null) return "";
        int colon = userId.indexOf(':');
        int start = userId.startsWith("@") ? 1 : 0;
        return colon > start ? userId.substring(start, colon) : userId.substring(start);
    }

    /** True if {@code userId} is a bridge bot (e.g. {@code @whatsappbot:server}). */
    static boolean isBot(String userId) {
        Network n = Network.fromUserId(userId);
        return n != null && Objects.equals(localpart(userId), n.botLocalpart());
    }

    private static JsonNode prevContent(JsonNode ev) {
        JsonNode p = ev.path("unsigned").path("prev_content");
        return p.isMissingNode() ? ev.path("prev_content") : p;
    }

    static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) if (n.isTextual()) out.add(n.asText());
        return List.copyOf(out);
    }
}
