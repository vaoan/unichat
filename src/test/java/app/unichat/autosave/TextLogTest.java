package app.unichat.autosave;

import app.unichat.Present;
import app.unichat.model.Message;
import app.unichat.model.MessageStatus;
import app.unichat.model.MessageType;
import app.unichat.support.PathParts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextLogTest {
    private static final String CHAT = "!family:demo";
    private static final Instant SENT = Instant.parse("2026-11-18T14:03:00Z");

    @TempDir
    Path dir;
    private Instant now = Instant.parse("2026-11-18T14:03:05Z");

    private TextLog log() {
        return new TextLog(dir, () -> now);
    }

    private static Message text(String id, String body) {
        Message m = new Message(id, CHAT, "@whatsapp_57300:demo", "Ana", false, SENT);
        m.setType(MessageType.TEXT);
        m.setBody(body);
        return m;
    }

    private SavedText seen(Message m) {
        return Present.of(SavedText.of(m, now));
    }

    @Test
    void aMessageIsWrittenOnceAndEachEditAddsAVersion() throws Exception {
        TextLog log = log();
        assertTrue(log.record(CHAT, List.of(seen(text("$1", "mañana no puedo")))));
        assertFalse(log.record(CHAT, List.of(seen(text("$1", "mañana no puedo")))), "the same again: nothing");
        now = now.plusSeconds(60);
        assertTrue(log.record(CHAT, List.of(seen(text("$1", "mañana sí puedo")))), "edited");
        assertEquals(List.of("mañana no puedo", "mañana sí puedo"), log.versions(CHAT, "$1").stream().map(SavedText::text).toList(),
                "oldest first");
        assertEquals(List.of(), log.versions(CHAT, "$2"), "never kept: no copy");
        assertFalse(log().record(CHAT, List.of(seen(text("$1", "mañana sí puedo")))), "known again after a restart, from the file");
        try (var files = Files.list(dir.resolve(TextLog.HERE))) {
            assertEquals(List.of(TextLog.key(CHAT) + " 2026-11.jsonl"), files.map(PathParts::name).toList(),
                    "this computer's file of the chat for the month");
        }
    }

    @Test
    void whatAnotherComputerKeptCountsTooEachVersionOnce() throws Exception {
        TextLog log = log();
        log.record(CHAT, List.of(seen(text("$1", "hola"))));
        Path other = Files.createDirectories(dir.resolve("0b6a3f3e-0d1c-4c0e-9a51-6f2d7c1e2b44"));
        Files.writeString(other.resolve(TextLog.key(CHAT) + " 2026-10.jsonl"),
                Present.of(SavedText.of(text("$0", "lo que la laptop vio"), SENT.minusSeconds(86_400 * 30))).json() + "\n"
                        + Present.of(SavedText.of(text("$1", "hola"), SENT.minusSeconds(5))).json() + "\n"
                        + "{esto no es json\n"
                        + "{\"id\": \"$9\"}\n"
                        + Present.of(SavedText.of(text("$2", "cortado"), SENT)).json().substring(0, 20), StandardCharsets.UTF_8);
        assertEquals(List.of("lo que la laptop vio"), log.versions(CHAT, "$0").stream().map(SavedText::text).toList());
        List<SavedText> hello = log.versions(CHAT, "$1");
        assertEquals(1, hello.size(), "seen by both: once");
        assertEquals(SENT.minusSeconds(5), hello.get(0).seen(), "when it was first seen, wherever");
        assertEquals(List.of("$0", "$1"), log.merged(CHAT).stream().map(SavedText::id).toList(),
                "broken and incomplete lines left out");
    }

    @Test
    void aWriteCutShortDoesntSpoilTheNextOne() throws Exception {
        Path own = Files.createDirectories(dir.resolve(TextLog.HERE));
        Files.writeString(own.resolve(TextLog.key(CHAT) + " 2026-11.jsonl"), "{\"id\":\"$0\",\"te", StandardCharsets.UTF_8); // a full disk
        TextLog log = log();
        assertTrue(log.record(CHAT, List.of(seen(text("$1", "hola")))));
        assertEquals(List.of("hola"), log.versions(CHAT, "$1").stream().map(SavedText::text).toList(), "on a line of its own");
    }

    @Test
    void onlyWhatWasSaidIsKept() {
        Message deleted = text("$1", "");
        deleted.markDeleted();
        assertNull(SavedText.of(deleted, SENT));
        assertNull(SavedText.of(text("$2", "   "), SENT), "nothing said");
        Message photo = text("$3", "IMG_1.jpg");
        photo.setType(MessageType.IMAGE);
        assertNull(SavedText.of(photo, SENT), "media is auto-save's other kinds");
        Message sending = text("local:uc1", "va saliendo");
        sending.setStatus(MessageStatus.SENDING);
        assertNull(SavedText.of(sending, SENT), "its id isn't the one it will have");
        Message emote = text("$4", "saluda");
        emote.setType(MessageType.EMOTE);
        assertEquals("saluda", Present.of(SavedText.of(emote, SENT)).text());
    }

    @Test
    void theKeyIsTheSameOnEveryComputerAndSaysNothingOfTheChat() {
        assertEquals(TextLog.key(CHAT), TextLog.key(CHAT));
        assertTrue(TextLog.key(CHAT).matches("[0-9a-f]{12}"), TextLog.key(CHAT));
        assertNotEquals(TextLog.key("!otro:demo"), TextLog.key(CHAT));
    }
}
