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
    /** Relation events (edits, reactions) whose target is not loaded yet, by target id. */
    final Map<String, List<JsonNode>> pending = new HashMap<>();
    /** Timestamp of the newest edit applied per message, so out-of-order edits don't win. */
    final Map<String, Long> editTs = new HashMap<>();

    String name;
    String avatar;
    String topic;
    String canonicalAlias;
    String bridgeProtocol;
    /** {@code com.beeper.room_type} from bridgev2 bridge info: dm, group or space. */
    String bridgeRoomType;
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

    MatrixRoom(String id) {
        this.id = id;
        this.chat = new Chat(id);
    }

    boolean isActiveMember(String userId) {
        String m = membership.get(userId);
        return "join".equals(m) || "invite".equals(m);
    }
}
