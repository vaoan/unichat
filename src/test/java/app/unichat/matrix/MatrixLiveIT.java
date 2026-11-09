package app.unichat.matrix;

import app.unichat.model.Chat;
import app.unichat.model.ConnectionState;
import app.unichat.model.Message;
import app.unichat.model.MessageStatus;
import app.unichat.model.MessageType;
import app.unichat.service.ChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end test against a real homeserver with users {@code alice} and {@code bob}
 * (password {@code pass1234}). Run with e.g.
 * {@code UNICHAT_IT_HOMESERVER=http://localhost:18008 ./mvnw test -Dtest=MatrixLiveIT}.
 * alice drives {@link MatrixChatService}; bob talks raw HTTP.
 */
@EnabledIfEnvironmentVariable(named = "UNICHAT_IT_HOMESERVER", matches = ".+")
class MatrixLiveIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String hs = System.getenv("UNICHAT_IT_HOMESERVER");
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void fullConversationRoundTrip() throws Exception {
        MatrixChatService alice = MatrixLogin.login(hs, "alice", "pass1234").join();
        List<Message> notified = new CopyOnWriteArrayList<>();
        alice.addListener(new ChatService.Listener() {
            @Override public void incomingMessage(Chat chat, Message m) { notified.add(m); }
        });
        changes = app.unichat.service.Changes.of(alice);
        alice.start();
        try {
            await("online", () -> alice.connectionState() == ConnectionState.ONLINE);

            String bob = bobLogin();
            String roomName = "IT " + UUID.randomUUID().toString().substring(0, 6);
            String roomId = bobPost(bob, "/createRoom", "{\"name\":\"" + roomName + "\",\"invite\":[\"" + alice.myUserId() + "\"],\"preset\":\"private_chat\"}")
                    .get("room_id").asText();

            await("invite auto-joined", () -> alice.chat(roomId) != null && alice.chat(roomId).title().equals(roomName));
            Chat chat = alice.chat(roomId);

            bobSend(bob, roomId, "hola alice");
            await("incoming message", () -> body(alice, roomId, "hola alice") != null);
            await("notification", () -> notified.stream().anyMatch(m -> m.body().equals("hola alice")));
            await("unread", chat::isUnread);
            Message incoming = body(alice, roomId, "hola alice");
            assertTrue(!incoming.outgoing());

            alice.markRead(roomId);
            await("read", () -> !chat.isUnread());

            alice.sendText(roomId, "hola bob", incoming.id()).join();
            await("sent", () -> body(alice, roomId, "hola bob") != null && body(alice, roomId, "hola bob").status() != MessageStatus.SENDING);
            Message mine = body(alice, roomId, "hola bob");
            assertEquals(incoming.id(), mine.replyToId());

            alice.toggleReaction(roomId, incoming.id(), "👍").join();
            await("reaction added", () -> incoming.reactions().getOrDefault("👍", java.util.Set.of()).contains(alice.myUserId()));
            alice.toggleReaction(roomId, incoming.id(), "👍").join();
            await("reaction removed", () -> !incoming.reactions().containsKey("👍"));

            alice.editMessage(roomId, mine.id(), "hola bob (editado)").join();
            await("edited", () -> mine.edited() && mine.body().equals("hola bob (editado)"));
            alice.deleteMessage(roomId, mine.id()).join();
            await("deleted", mine::deleted);

            alice.setFavorite(roomId, true).join();
            alice.setArchived(roomId, true).join();
            alice.setMarkedUnread(roomId, true).join();
            await("tags", () -> chat.favorite() && chat.archived() && chat.markedUnread());
            alice.setArchived(roomId, false).join();
            alice.markRead(roomId);
            await("untagged", () -> !chat.archived() && !chat.markedUnread());

            Path png = Files.createTempFile("it", ".png");
            ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", png.toFile());
            alice.sendFile(roomId, png, null).join();
            await("image sent", () -> alice.messages(roomId).stream()
                    .anyMatch(m -> m.type() == MessageType.IMAGE && m.outgoing() && m.status() == MessageStatus.SENT));
            Message sentImg = alice.messages(roomId).stream().filter(m -> m.type() == MessageType.IMAGE).reduce((a, b) -> b).orElseThrow();
            assertEquals(40, sentImg.width());

            String mxc = bobUpload(bob, png);
            bobPut(bob, "/rooms/" + enc(roomId) + "/send/m.room.message/" + UUID.randomUUID(),
                    "{\"msgtype\":\"m.image\",\"body\":\"bob.png\",\"url\":\"" + mxc + "\",\"info\":{\"w\":40,\"h\":30,\"mimetype\":\"image/png\"}}");
            await("image received", () -> body(alice, roomId, "bob.png") != null);
            Message img = body(alice, roomId, "bob.png");
            assertEquals(MessageType.IMAGE, img.type());
            assertEquals(mxc, img.mediaUrl());
            Path downloaded = alice.media(img.mediaUrl(), 0, 0).join();
            assertTrue(Files.size(downloaded) > 0);
            Path thumb = alice.media(img.mediaUrl(), 32, 32).join();
            assertTrue(Files.exists(thumb));

            bobPut(bob, "/rooms/" + enc(roomId) + "/typing/" + enc("@bob:" + alice.myUserId().split(":", 2)[1]),
                    "{\"typing\":true,\"timeout\":10000}");
            await("typing", () -> !chat.typing().isEmpty());

            for (int i = 0; i < 40; i++) bobSend(bob, roomId, "relleno " + i);
            await("burst", () -> body(alice, roomId, "relleno 39") != null);
            alice.loadOlder(roomId).join();
            assertNotNull(alice.messages(roomId));

            alice.leaveChat(roomId).join();
            await("left", () -> alice.chat(roomId) == null || alice.chat(roomId).archived());
        } finally {
            alice.logout().join();
            alice.stop();
        }
    }

    private static Message body(MatrixChatService svc, String roomId, String text) {
        return svc.messages(roomId).stream().filter(m -> m.body().equals(text) || text.equals(m.fileName())).findFirst().orElse(null);
    }

    /** alice's changes: each wait reacts to them. */
    private static app.unichat.service.Changes changes;

    private static void await(String what, BooleanSupplier cond) throws InterruptedException {
        changes.until(what, cond);
    }

    private String bobLogin() throws Exception {
        String body = "{\"type\":\"m.login.password\",\"identifier\":{\"type\":\"m.id.user\",\"user\":\"bob\"},\"password\":\"pass1234\"}";
        return call("POST", "/login", null, body).get("access_token").asText();
    }

    private String bobUpload(String token, Path file) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(hs + "/_matrix/media/v3/upload?filename=bob.png"))
                .header("Content-Type", "image/png")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofFile(file)).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(r.body()).get("content_uri").asText();
    }

    private void bobSend(String token, String roomId, String text) throws Exception {
        bobPut(token, "/rooms/" + enc(roomId) + "/send/m.room.message/" + UUID.randomUUID(),
                "{\"msgtype\":\"m.text\",\"body\":\"" + text + "\"}");
    }

    private JsonNode bobPost(String token, String path, String body) throws Exception {
        return call("POST", path, token, body);
    }

    private JsonNode bobPut(String token, String path, String body) throws Exception {
        return call("PUT", path, token, body);
    }

    private JsonNode call(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(hs + "/_matrix/client/v3" + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 300) throw new IllegalStateException(method + " " + path + " -> " + r.statusCode() + " " + r.body());
        return JSON.readTree(r.body());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
