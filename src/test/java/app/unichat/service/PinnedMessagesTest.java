package app.unichat.service;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.model.PinnedMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A chat's pinned messages: kept with what they said, newest pin first, and still there after a restart. */
class PinnedMessagesTest {
    @TempDir Path dir;

    private static Message message(String id, String body, Instant at) {
        Message m = new Message(id, "!c", "@ana", "Ana", false, at);
        m.setType(MessageType.TEXT);
        m.setBody(body);
        return m;
    }

    @Test
    void aPinKeepsWhatTheMessageSaidSoItShowsEvenWhenTheMessageIsntLoaded() throws Exception {
        PinnedMessages pins = new PinnedMessages(dir.resolve("pinned-messages.json"));
        List<String> changed = new ArrayList<>();
        pins.addListener(changed::add);
        Instant long_ago = Instant.parse("2025-01-02T03:04:05Z");
        pins.pin("!c", message("$old", "la dirección es Calle 5 #12", long_ago));
        pins.pin("!c", message("$new", "nos vemos el sábado", Instant.parse("2026-11-01T00:00:00Z")));

        List<PinnedMessage> list = pins.of("!c");
        assertEquals(List.of("$new", "$old"), list.stream().map(PinnedMessage::messageId).toList(), "newest pin first");
        assertEquals("la dirección es Calle 5 #12", list.get(1).preview());
        assertEquals("Ana", list.get(1).sender());
        assertEquals(long_ago, list.get(1).sentAt());
        assertTrue(pins.isPinned("!c", "$old"));
        assertEquals(List.of("!c", "!c"), changed, "each change is told, with its chat");

        pins.flush();
        PinnedMessages again = new PinnedMessages(dir.resolve("pinned-messages.json"));
        assertEquals(List.of("$new", "$old"), again.of("!c").stream().map(PinnedMessage::messageId).toList(), "after a restart");

        again.unpin("!c", "$new");
        assertFalse(again.isPinned("!c", "$new"));
        assertEquals(List.of(), again.of("!other"), "another chat has none");
        again.flush(); // what it writes in the background is done before the folder goes
    }

    @Test
    void pinsWrittenByAnotherComputerAreReadAgainAndTheirChatsTold() throws Exception {
        Path file = dir.resolve("pinned-messages.json");
        PinnedMessages pins = new PinnedMessages(file);
        pins.pin("!mine", message("$a", "hola", Instant.now()));
        pins.flush();
        List<String> changed = new ArrayList<>();
        pins.addListener(changed::add);
        java.nio.file.Files.writeString(file, "{\"!theirs\":[{\"id\":\"$b\",\"sender\":\"Ana\",\"preview\":\"nos vemos\"}]}");
        pins.reload();
        assertEquals(List.of(), pins.of("!mine"));
        assertEquals("nos vemos", pins.of("!theirs").getFirst().preview());
        assertEquals(java.util.Set.of("!mine", "!theirs"), new java.util.HashSet<>(changed), "both chats' bars redraw");

        java.nio.file.Files.writeString(file, "");
        pins.reload();
        assertEquals(List.of(), pins.of("!theirs"), "an empty file is no pins");
        pins.flush();
    }

    @Test
    void pinningTwiceKeepsOneAndALongPreviewIsCut() {
        PinnedMessages pins = new PinnedMessages(dir.resolve("p.json"));
        Message m = message("$a", "x".repeat(1000), Instant.now());
        pins.pin("!c", m);
        pins.pin("!c", m);
        assertEquals(1, pins.of("!c").size());
        assertTrue(pins.of("!c").getFirst().preview().length() <= PinnedMessages.MAX_PREVIEW + 1);
        pins.flush();
    }

    @Test
    void aDamagedFileIsNoPinsNotAFailure() throws Exception {
        Path f = java.nio.file.Files.writeString(dir.resolve("p.json"), "{ esto no es json");
        assertEquals(List.of(), new PinnedMessages(f).of("!c"));
    }
}
