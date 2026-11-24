package app.unichat.service;

import app.unichat.Present;
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
    void mutingAChatArchivesItAndUnarchivingUnmutesIt() throws Exception {
        MockChatService svc = new MockChatService();
        svc.setMuted("!mom:demo", true).get();
        assertTrue(Present.of(svc.chat("!mom:demo")).archived());
        svc.setArchived("!mom:demo", false).get();
        assertFalse(Present.of(svc.chat("!mom:demo")).muted(), "back in the inbox, it notifies again");
        svc.setArchived("!mom:demo", true).get();
        assertTrue(Present.of(svc.chat("!mom:demo")).muted(), "archived = silenced for good");
        svc.setMuted("!mom:demo", false).get();
        assertFalse(Present.of(svc.chat("!mom:demo")).archived());
    }

    @Test
    void archivingAServerSilencesItsChannelsButMutingAChannelLeavesTheServer() throws Exception {
        MockChatService svc = new MockChatService();
        svc.setMuted("!srv-memes:demo", true).get();
        assertTrue(Present.of(svc.chat("!srv-memes:demo")).archived());
        assertFalse(Present.of(svc.chat("!server:demo")).archived(), "one quiet channel doesn't hide its server");

        svc.setArchived("!server:demo", true).get();
        for (String id : List.of("!server:demo", "!srv-general:demo", "!srv-memes:demo")) {
            assertTrue(Present.of(svc.chat(id)).archived() && Present.of(svc.chat(id)).muted(), id);
        }
        svc.setArchived("!server:demo", false).get();
        assertFalse(Present.of(svc.chat("!srv-general:demo")).muted());
        assertFalse(Present.of(svc.chat("!srv-general:demo")).archived());
    }

    @Test
    void chatsMutedElsewhereAreTheOnesToArchive() throws Exception {
        Chat a = new Chat("!a");
        Chat b = new Chat("!b");
        Chat c = new Chat("!c");
        a.setMuted(true);
        b.setMuted(true);
        b.setArchived(true);
        c.setMuted(false);
        assertEquals(List.of("!a"), QuietRule.toArchive(Set.of(), List.of(a, b, c)), "first sync: what's muted gets archived");
        assertEquals(List.of(), QuietRule.toArchive(Set.of("!a"), List.of(a, b, c)), "already muted before: the user may have just unarchived");
    }

    @Test
    void aChannelArrivingInAnArchivedServerGoesQuietToo() throws Exception {
        Chat server = new Chat("!srv");
        Chat category = new Chat("!cat");
        Chat old = new Chat("!old");
        Chat fresh = new Chat("!new");
        Chat elsewhere = new Chat("!dm");
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
