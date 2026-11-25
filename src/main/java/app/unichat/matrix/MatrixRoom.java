package app.unichat.matrix;

import app.unichat.model.Chat;
import app.unichat.model.Member;
import app.unichat.model.Message;
import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

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

    @Nullable String name;
    @Nullable String avatar;
    @Nullable String topic;
    @Nullable String canonicalAlias;
    @Nullable String bridgeProtocol;
    /** Content of com.beeper.room_features (what the network supports), or null. */
    @Nullable JsonNode roomFeatures;
    /** {@code com.beeper.room_type} from bridgev2 bridge info: dm, group or space. */
    @Nullable String bridgeRoomType;
    /** {@code m.room.create} type m.space. */
    boolean space;
    /** {@code channel.id} of m.bridge ("__personal…" marks the bridge's folder space). */
    @Nullable String bridgeChannelId;
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
    @Nullable Instant invitedAt;
    @Nullable String inviter;
    @Nullable String prevBatch;

    /** History goes on while the server gave a point to read back from ({@code prev_batch}, then each page's {@code end}). */
    boolean hasOlder() { return prevBatch != null; }

    /** Names in the last m.tag; null until one is heard. Only input for the preferences file (see {@link RoomPreferences}). */
    java.util.@Nullable Set<String> serverTags;
    /** Last reminder account data; {@link #reminderKnown} once heard, null when it's empty. */
    @Nullable Instant serverReminder;
    boolean reminderKnown;

    MatrixRoom(String id) {
        this.id = id;
        this.chat = new Chat(id);
    }

    /** In time order: a catch-up's older events come before what's already there, not after it. */
    void addInOrder(Message m) {
        int at = timeline.size();
        while (at > 0 && m.timestamp() != null && timeline.get(at - 1).timestamp() != null
                && timeline.get(at - 1).timestamp().isAfter(m.timestamp())) at--;
        timeline.add(at, m);
    }

    boolean isActiveMember(String userId) {
        String m = membership.get(userId);
        return "join".equals(m) || "invite".equals(m);
    }
}
