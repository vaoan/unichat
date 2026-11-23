package app.unichat.matrix;

import app.unichat.support.I18n;
import com.fasterxml.jackson.databind.JsonNode;

import static app.unichat.matrix.SyncEvents.blankToNull;
import static app.unichat.matrix.SyncEvents.isBot;
import static app.unichat.matrix.SyncEvents.prevContent;
import static app.unichat.matrix.SyncEvents.text;

/** The line a room's state change shows in the timeline ("Ana joined"). */
final class SystemMessages {
    private final RoomFields fields;

    SystemMessages(RoomFields fields) {
        this.fields = fields;
    }

    String of(MatrixRoom room, JsonNode ev) {
        String type = text(ev, "type");
        String sender = text(ev, "sender");
        if (isBot(sender) || !fields.isGroup(room)) return null;
        String sn = fields.nameOf(room, sender);
        JsonNode c = ev.path("content");
        return switch (type) {
            case "m.room.member" -> {
                String target = text(ev, "state_key");
                if (target == null || isBot(target)) yield null;
                JsonNode prev = prevContent(ev);
                String mem = text(c, "membership");
                String prevMem = text(prev, "membership");
                String dn = text(c, "displayname");
                String tn = dn != null ? dn : fields.nameOf(room, target);
                if (mem == null) yield null;
                yield switch (mem) {
                    case "join" -> {
                        if (!"join".equals(prevMem)) yield I18n.t("system.joined", tn);
                        String pn = text(prev, "displayname");
                        yield pn != null && dn != null && !pn.equals(dn) ? I18n.t("system.renamed", pn, dn) : null;
                    }
                    case "invite" -> I18n.t("system.invited", sn, tn);
                    case "leave" -> target.equals(sender)
                            ? ("invite".equals(prevMem) ? I18n.t("system.declined", tn) : I18n.t("system.left", tn))
                            : I18n.t("system.removed", sn, tn);
                    case "ban" -> I18n.t("system.banned", sn, tn);
                    default -> null;
                };
            }
            case "m.room.name" -> {
                String n = blankToNull(text(c, "name"));
                yield n == null ? I18n.t("system.nameRemoved", sn) : I18n.t("system.nameChanged", sn, n);
            }
            case "m.room.topic" -> I18n.t("system.topicChanged", sn);
            case "m.room.avatar" -> I18n.t("system.avatarChanged", sn);
            default -> null;
        };
    }
}
