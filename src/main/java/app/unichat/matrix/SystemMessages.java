package app.unichat.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Function;

import static app.unichat.matrix.SyncEvents.*;

/** The line a room's state change shows in the timeline ("Ana se unió"). */
final class SystemMessages {
    private final RoomFields fields;
    private final Function<String, MatrixRoom> room;

    SystemMessages(RoomFields fields, Function<String, MatrixRoom> room) {
        this.fields = fields;
        this.room = room;
    }

    String of(MatrixRoom room, JsonNode ev) {
        String type = text(ev, "type");
        String sender = text(ev, "sender");
        if (isBot(sender) || !fields.isGroup(room)) return null;
        String sn = fields.nameOf(room, sender);
        JsonNode c = ev.path("content");
        switch (type) {
            case "m.room.member" -> {
                String target = text(ev, "state_key");
                if (target == null || isBot(target)) return null;
                JsonNode prev = prevContent(ev);
                String mem = text(c, "membership");
                String prevMem = text(prev, "membership");
                String dn = text(c, "displayname");
                String tn = dn != null ? dn : fields.nameOf(room, target);
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
}
