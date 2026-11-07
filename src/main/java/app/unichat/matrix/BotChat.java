package app.unichat.matrix;

import app.unichat.model.Message;
import app.unichat.model.Network;
import app.unichat.service.ChatService.Listener;
import app.unichat.settings.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static app.unichat.matrix.BridgeProtocol.botCommand;
import static app.unichat.matrix.BridgeProtocol.brokenRoom;
import static app.unichat.matrix.BridgeProtocol.mergeDirect;
import static app.unichat.matrix.MatrixClient.CLIENT;
import static app.unichat.support.Urls.enc;

/** The chat with each bridge's bot: finds or creates it, sends commands and waits for their replies. */
final class BotChat {
    private final MatrixCore core;
    private final MatrixClient client;
    private final SyncProcessor proc;
    private final String userId;
    private final String serverName;
    private final MessageSender sender;
    /** One command at a time per bridge: replies are matched by order in the bot chat. */
    private final Map<Network, Object> botLocks = new ConcurrentHashMap<>();

    BotChat(MatrixCore core, MessageSender sender) {
        this.core = core;
        this.client = core.client;
        this.proc = core.proc;
        this.userId = core.userId;
        this.serverName = core.serverName;
        this.sender = sender;
    }

    String botId(Network n) { return "@" + n.botLocalpart() + ":" + serverName; }

    /** The chat with {@code n}'s bridge bot, created when there's none (logins through the bridge API don't make one). */
    String room(Network n) throws IOException, InterruptedException {
        String roomId = find(n);
        if (roomId != null) return roomId;
        String bot = botId(n);
        ObjectNode body = MatrixClient.JSON.createObjectNode();
        body.put("is_direct", true);
        body.put("preset", "trusted_private_chat");
        body.putArray("invite").add(bot);
        roomId = client.post(CLIENT + "/createRoom", body).path("room_id").asText(null);
        if (roomId == null) throw new MatrixException(0, null, "No se pudo crear el chat con el bot");
        addDirect(bot, roomId);
        waitForJoin(roomId, bot);
        return roomId;
    }

    private void abandon(String roomId) {
        for (String step : List.of("/leave", "/forget")) {
            try {
                client.post(CLIENT + "/rooms/" + enc(roomId) + step, null);
            } catch (Exception ignored) {
                // A broken room may refuse even this; dropping it locally is what matters.
            }
        }
        synchronized (proc) {
            proc.removeRoom(roomId);
        }
        // If the server still lists it as joined, never pick it as the bot chat again.
        Settings.get().addToSet("matrix.brokenRooms", roomId);
        core.fire(Listener::chatsChanged);
    }

    /** Sends a command to {@code n}'s bridge bot and returns its next reply (up to 20 s). */
    String query(Network n, String command) throws IOException, InterruptedException {
        return query(n, command, 20);
    }

    String query(Network n, String command, int seconds) throws IOException, InterruptedException {
        synchronized (botLocks.computeIfAbsent(n, k -> new Object())) {
            return queryLocked(n, command, seconds);
        }
    }

    /** Sends a command through the existing bot chat and returns its next reply; null with no chat, or no reply in 10 s. */
    String ask(Network n, String command) throws IOException, InterruptedException {
        return ask(n, command, 10);
    }

    String ask(Network n, String command, int seconds) throws IOException, InterruptedException {
        synchronized (botLocks.computeIfAbsent(n, k -> new Object())) {
            String roomId = find(n);
            if (roomId == null) return null;
            int before = messageCount(roomId);
            send(roomId, botCommand(n, command));
            for (int i = 0; i < seconds * 2; i++) {
                Thread.sleep(500);
                String reply = replyAfter(roomId, before);
                if (reply != null) return reply;
            }
            return null;
        }
    }

    private String queryLocked(Network n, String command, int seconds) throws IOException, InterruptedException {
        String roomId = room(n);
        int before = messageCount(roomId);
        try {
            send(roomId, botCommand(n, command));
        } catch (RuntimeException | IOException e) {
            if (!brokenRoom(e)) throw e;
            // The homeserver lost this room's state (nothing can be sent there any more): start a fresh bot chat.
            abandon(roomId);
            roomId = room(n);
            before = messageCount(roomId);
            send(roomId, botCommand(n, command));
        }
        for (int i = 0; i < seconds * 2; i++) {
            Thread.sleep(500);
            String reply = replyAfter(roomId, before);
            if (reply != null) return reply;
        }
        throw new MatrixException(0, null, "El puente de " + n.displayName() + " no respondió");
    }

    String find(Network n) {
        Set<String> broken = Settings.get().getSet("matrix.brokenRooms");
        synchronized (proc) {
            for (MatrixRoom r : proc.rooms()) {
                if (r.chat.bridgeBot() && r.chat.network() == n && !r.invite && !broken.contains(r.id)) return r.id;
            }
        }
        return null;
    }

    int messageCount(String roomId) {
        synchronized (proc) {
            MatrixRoom r = proc.room(roomId);
            return r == null ? 0 : (int) r.timeline.stream().filter(m -> !m.outgoing()).count();
        }
    }

    String replyAfter(String roomId, int before) {
        synchronized (proc) {
            MatrixRoom r = proc.room(roomId);
            if (r == null) return null;
            List<Message> incoming = r.timeline.stream().filter(m -> !m.outgoing()).toList();
            return incoming.size() > before ? incoming.get(incoming.size() - 1).body() : null;
        }
    }

    void send(String roomId, String text) throws IOException {
        boolean known;
        synchronized (proc) {
            known = proc.room(roomId) != null;
        }
        if (known) {
            sender.sendText(roomId, text, null).join();
        } else {
            // Not in the sync yet: no chat to show an echo in.
            client.sendEvent(roomId, "m.room.message", core.newTxnId(), MatrixClient.JSON.createObjectNode().put("msgtype", "m.text").put("body", text));
        }
    }

    private void addDirect(String otherUser, String roomId) throws IOException {
        ObjectNode cached;
        synchronized (proc) {
            cached = proc.directContentCopy();
        }
        String path = CLIENT + "/user/" + enc(userId) + "/account_data/m.direct";
        JsonNode server = null;
        try {
            server = client.get(path);
        } catch (Exception e) {
            // no m.direct on the server yet (404) or transient failure: fall back to the cached copy
        }
        client.put(path, mergeDirect(server, cached, otherUser, roomId));
    }

    private void waitForJoin(String roomId, String user) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            try {
                JsonNode m = client.get(CLIENT + "/rooms/" + enc(roomId) + "/state/m.room.member/" + enc(user));
                if ("join".equals(m.path("membership").asText())) return;
            } catch (Exception ignored) {
                // not yet
            }
            Thread.sleep(1000);
        }
    }
}
