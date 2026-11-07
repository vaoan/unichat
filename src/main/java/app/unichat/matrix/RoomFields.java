package app.unichat.matrix;

import app.unichat.model.Chat;
import app.unichat.model.Member;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.model.Network;
import app.unichat.model.RoomFeatures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static app.unichat.matrix.SyncEvents.*;

/** What a chat shows, worked out from its room's state: title, network, group or not, members. */
final class RoomFields {
    private final String me;
    private final Map<String, String> directRoomUser;
    private final Function<String, MatrixRoom> room;

    RoomFields(String me, Map<String, String> directRoomUser, Function<String, MatrixRoom> room) {
        this.me = me;
        this.directRoomUser = directRoomUser;
        this.room = room;
    }

    /** Recomputes title, avatar, network, group, members and preview of a room's chat. */
    void refresh(MatrixRoom room) {
        Chat c = room.chat;
        boolean botRoom = isBridgeBotRoom(room);
        c.setBridgeBot(botRoom);
        c.setInvite(room.invite);
        c.setNetwork(detectNetwork(room));
        c.setFeatures(room.roomFeatures != null ? RoomFeatures.parse(room.roomFeatures)
                : c.network() == Network.DISCORD ? RoomFeatures.discord() : RoomFeatures.UNKNOWN);
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
        if (!members.equals(c.members())) c.setMembers(members);

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

    private Network detectNetwork(MatrixRoom room) {
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

    boolean isGroup(MatrixRoom room) {
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
    List<String> otherUsers(MatrixRoom room) {
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

    String nameOf(MatrixRoom room, String userId) {
        Member m = room.members.get(userId);
        if (m != null && m.name() != null && !m.name().isBlank()) return m.name();
        return fallbackName(userId);
    }
}
