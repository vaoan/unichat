package app.unichat.service;

import app.unichat.model.Chat;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Muted means archived: archiving silences for good, bringing a chat back makes it notify again. */
class QuietRuleTest {
    @Test
    void mutingAChatArchivesItAndUnarchivingUnmutesIt() {
        MockChatService svc = new MockChatService();
        svc.setMuted("!mama:demo", true).join();
        assertTrue(svc.chat("!mama:demo").archived());
        svc.setArchived("!mama:demo", false).join();
        assertFalse(svc.chat("!mama:demo").muted(), "back in the inbox, it notifies again");
        svc.setArchived("!mama:demo", true).join();
        assertTrue(svc.chat("!mama:demo").muted(), "archived = silenced for good");
        svc.setMuted("!mama:demo", false).join();
        assertFalse(svc.chat("!mama:demo").archived());
    }

    @Test
    void archivingAServerSilencesItsChannelsButMutingAChannelLeavesTheServer() {
        MockChatService svc = new MockChatService();
        svc.setMuted("!srv-memes:demo", true).join();
        assertTrue(svc.chat("!srv-memes:demo").archived());
        assertFalse(svc.chat("!servidor:demo").archived(), "one quiet channel doesn't hide its server");

        svc.setArchived("!servidor:demo", true).join();
        for (String id : List.of("!servidor:demo", "!srv-general:demo", "!srv-memes:demo")) {
            assertTrue(svc.chat(id).archived() && svc.chat(id).muted(), id);
        }
        svc.setArchived("!servidor:demo", false).join();
        assertFalse(svc.chat("!srv-general:demo").muted());
        assertFalse(svc.chat("!srv-general:demo").archived());
    }

    @Test
    void chatsMutedElsewhereAreTheOnesToArchive() {
        Chat a = new Chat("!a"), b = new Chat("!b"), c = new Chat("!c");
        a.setMuted(true);
        b.setMuted(true);
        b.setArchived(true);
        c.setMuted(false);
        assertEquals(List.of("!a"), QuietRule.toArchive(Set.of(), List.of(a, b, c)), "first sync: what's muted gets archived");
        assertEquals(List.of(), QuietRule.toArchive(Set.of("!a"), List.of(a, b, c)), "already muted before: the user may have just unarchived");
    }

    @Test
    void aChannelArrivingInAnArchivedServerGoesQuietToo() {
        Chat server = new Chat("!srv"), category = new Chat("!cat"), old = new Chat("!old"), fresh = new Chat("!new"),
                elsewhere = new Chat("!dm");
        server.setSpace(true);
        server.setArchived(true);
        server.setSpaceChildren(List.of("!cat"));
        category.setSpace(true);
        category.setArchived(true);
        category.setSpaceChildren(List.of("!old"));
        old.setArchived(true);
        old.setSpaceParents(Set.of("!cat"));
        fresh.setSpaceParents(Set.of("!cat"));
        List<Chat> now = List.of(server, category, old, fresh, elsewhere);
        assertEquals(List.of("!new"), QuietRule.toInherit(Set.of(), now), "first sync: whatever is out of line");
        assertEquals(List.of(), QuietRule.toInherit(Set.of("!srv", "!cat", "!old", "!new", "!dm"), now),
                "chats already known are left alone (the user may be bringing the server back)");
    }
}
