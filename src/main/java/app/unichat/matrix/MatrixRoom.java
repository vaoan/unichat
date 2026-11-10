package app.unichat.matrix;

import app.unichat.model.Chat;
import app.unichat.model.Member;
import app.unichat.model.Message;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Raw Matrix state of one room plus the live {@link Chat} and messages derived from it. Guarded by the processor's lock. */
final class MatrixRoom {
    /** One m.reaction event: who reacted with {@code key} to {@code targetId}. */
    record Reaction(String targetId, String key, String sender) {}

    final String id;
    final Chat chat;
    final List<Message> timeline = new ArrayList<>();
    final Map<String, Message> byId = new HashMap<>();
    /** userId -> membership (join, invite, leave, ban). */
    final Map<String, String> membership = new LinkedHashMap<>();
    /** Every user whose profile we have seen, including former members. */
    final Map<String, Member> members = new LinkedHashMap<>();
    /** reaction event id -> reaction. */
    final Map<String, Reaction> reactionEvents = new HashMap<>();
    /** Relation events (edits, reactions) whose target is not loaded yet, by target id; the last added for last. */
    final Map<String, List<JsonNode>> pending = new LinkedHashMap<>();
    /** Timestamp of the newest edit applied per message, so out-of-order edits don't win. */
    final Map<String, Long> editTs = new HashMap<>();

    String name;
    String avatar;
    String topic;
    String canonicalAlias;
    String bridgeProtocol;
    /** Content of com.beeper.room_features (what the network supports), or null. */
    com.fasterxml.jackson.databind.JsonNode roomFeatures;
    /** {@code com.beeper.room_type} from bridgev2 bridge info: dm, group or space. */
    String bridgeRoomType;
    /** {@code m.room.create} type m.space. */
    boolean space;
    /** {@code channel.id} of m.bridge ("__personal…" marks the bridge's folder space). */
    String bridgeChannelId;
    /** m.room.create was sent by a bridge bot. */
    boolean createdByBridge;
    /** m.space.child state: child room id → its order ("" when none), in arrival order. */
    final Map<String, String> spaceChildOrder = new LinkedHashMap<>();
    final java.util.Set<String> spaceParents = new java.util.LinkedHashSet<>();
    List<String> heroes = List.of();
    int joinedCount = -1;
    int invitedCount = -1;
    boolean directInvite;
    boolean invite;
    boolean markedUnreadStable;
    Instant created;
    Instant invitedAt;
    String inviter;
    String prevBatch;
    /** Names in the last m.tag; null until one is heard. Only input for the preferences file (see {@link RoomPreferences}). */
    java.util.Set<String> serverTags;
    /** Last reminder account data; {@link #reminderKnown} once heard, null when it's empty. */
    Instant serverReminder;
    boolean reminderKnown;

    MatrixRoom(String id) {
        this.id = id;
        this.chat = new Chat(id);
    }

    boolean isActiveMember(String userId) {
        String m = membership.get(userId);
        return "join".equals(m) || "invite".equals(m);
    }
}
