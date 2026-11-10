package app.unichat.ui.chats;

import app.unichat.service.Folder;
import app.unichat.model.Chat;
import app.unichat.model.Network;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FolderRowsTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static Chat chat(String id, int minutes) {
        Chat c = new Chat(id);
        c.setTitle(id);
        c.setPrefKey("key-" + id);
        c.setNetwork(Network.WHATSAPP);
        c.setLastActivity(T0.plusSeconds(minutes * 60L));
        return c;
    }

    private static Folder folder(String... ids) {
        return new Folder("f", "F", java.util.Arrays.stream(ids).map(i -> "key-" + i).toList());
    }

    private static List<String> ids(List<Chat> chats) {
        return chats.stream().map(Chat::id).toList();
    }

    @Test
    void aFoldersRowShowsItsMostRecentChatEvenIfAPinnedOneComesFirst() {
        Chat old = chat("old", 1);
        Chat recent = chat("recent", 9);
        assertSame(recent, FolderRows.latest(List.of(old, recent)));
        assertNull(FolderRows.latest(List.of()), "an empty folder says so");
    }

    @Test
    void keepsArchivedChats() {
        Chat a = chat("a", 1);
        a.setArchived(true);
        assertEquals(List.of("a"), ids(FolderRows.chatsIn(folder("a"), List.of(a))));
    }

    @Test
    void leavesOutChatsThatAreNotHereAndBridgeBotsAndThePersonalSpace() {
        Chat bot = chat("bot", 1);
        bot.setBridgeBot(true);
        Chat personal = chat("me", 2);
        personal.setSpace(true);
        personal.setPersonalSpace(true);
        Chat outside = chat("outside", 3);
        Chat a = chat("a", 4);
        assertEquals(List.of("a"),
                ids(FolderRows.chatsIn(folder("a", "bot", "me", "absent"), List.of(bot, personal, outside, a))));
    }

    @Test
    void pinnedFirstThenNewestLikeTheInbox() {
        Chat old = chat("old", 1);
        Chat recent = chat("recent", 5);
        Chat pinned = chat("pinned", 0);
        pinned.setPinned(true);
        assertEquals(List.of("pinned", "recent", "old"),
                ids(FolderRows.chatsIn(folder("old", "recent", "pinned"), List.of(old, recent, pinned))));
    }

    @Test
    void aServerSortsByItsNewestChannelLikeTheInbox() {
        Chat server = chat("server", 0);
        server.setSpace(true);
        server.setSpaceChildren(List.of("channel"));
        Chat channel = chat("channel", 9);
        Chat a = chat("a", 5);
        assertEquals(List.of("server", "a"),
                ids(FolderRows.chatsIn(folder("server", "a"), List.of(server, channel, a))));
    }

    @Test
    void unreadCountsLikeTheInbox() {
        Chat unread = chat("unread", 1);
        unread.setUnreadCount(4);
        Chat read = chat("read", 2);
        Chat muted = chat("muted", 3);
        muted.setUnreadCount(1);
        muted.setMuted(true);
        Chat archived = chat("archived", 4);
        archived.setUnreadCount(1);
        archived.setArchived(true);
        Chat outside = chat("outside", 5);
        outside.setUnreadCount(1);
        assertEquals(1, FolderRows.unread(folder("unread", "read", "muted", "archived"),
                List.of(unread, read, muted, archived, outside)));
    }

    @Test
    void aServerCountsItsUnreadChannelsOnce() {
        Chat server = chat("server", 0);
        server.setSpace(true);
        server.setSpaceChildren(List.of("channel", "quiet"));
        Chat channel = chat("channel", 1);
        channel.setUnreadCount(3);
        Chat quiet = chat("quiet", 2);
        assertEquals(1, FolderRows.unread(folder("server", "channel"), List.of(server, channel, quiet)),
                "the channel is in the folder twice: on its own and through its server");
    }

    @Test
    void anInvitationStaysOutUntilJoined() {
        Chat invited = chat("invited", 1);
        invited.setInvite(true);
        invited.setUnreadCount(1);
        assertFalse(FolderRows.canHold(invited), "its key is the room id: it wouldn't travel");
        assertEquals(List.of(), ids(FolderRows.chatsIn(folder("invited"), List.of(invited))));
        assertEquals(0, FolderRows.unread(folder("invited"), List.of(invited)));
        invited.setInvite(false);
        assertTrue(FolderRows.canHold(invited));
        assertEquals(List.of("invited"), ids(FolderRows.chatsIn(folder("invited"), List.of(invited))));
    }

    private static Chat server(String id, int minutes, String... channels) {
        Chat s = chat(id, minutes);
        s.setSpace(true);
        s.setSpaceChildren(List.of(channels));
        return s;
    }

    @Test
    void foldersOfOneRefreshShareTheTreePerNetwork() {
        Chat one = server("one", 0, "c1");
        Chat two = server("two", 0, "c2");
        Chat c1 = chat("c1", 1);
        c1.setUnreadCount(1);
        Chat c2 = chat("c2", 2);
        c2.setUnreadCount(1);
        Chat other = server("other", 0, "c3");
        other.setNetwork(Network.DISCORD);
        Chat c3 = chat("c3", 3);
        c3.setNetwork(Network.DISCORD);
        var servers = new FolderRows.Servers(List.of(one, two, c1, c2, other, c3));

        FolderRows.unread(folder("one"), servers);
        FolderRows.unread(folder("one", "two"), servers);
        FolderRows.chatsIn(folder("two"), servers);
        assertEquals(1, servers.treesBuilt(), "every server of the network comes from one tree");
        assertSame(servers.channels(one), servers.channels(one), "the same server in two folders");
        FolderRows.unread(folder("other"), servers);
        assertEquals(2, servers.treesBuilt());
    }

    @Test
    void noServerInAnyFolderBuildsNoTree() {
        Chat a = chat("a", 1);
        var servers = new FolderRows.Servers(List.of(a, server("s", 0, "a")));
        FolderRows.unread(folder("a"), servers);
        assertEquals(0, servers.treesBuilt());
    }

    @Test
    void theSharedTreeGivesTheSameRowsAsBuildingEachOne() {
        Chat outer = server("outer", 0, "inner", "c1", "not-joined");
        Chat inner = server("inner", 0, "c2");
        Chat c1 = chat("c1", 1);
        c1.setUnreadCount(2);
        Chat c2 = chat("c2", 9);
        c2.setUnreadCount(1);
        Chat quiet = server("quiet", 0, "c3");
        quiet.setMuted(true);
        Chat c3 = chat("c3", 3);
        c3.setUnreadCount(1);
        Chat alone = chat("alone", 5);
        alone.setUnreadCount(1);
        Chat pinned = chat("pinned", 0);
        pinned.setPinned(true);
        Chat elsewhere = server("elsewhere", 0, "c4");
        elsewhere.setNetwork(Network.DISCORD);
        Chat c4 = chat("c4", 7);
        c4.setNetwork(Network.DISCORD);
        c4.setUnreadCount(1);
        List<Chat> all = List.of(outer, inner, c1, c2, quiet, c3, alone, pinned, elsewhere, c4);

        var servers = new FolderRows.Servers(all);
        for (var f : List.of(folder("outer", "alone", "pinned"), folder("inner", "c1"), folder("quiet", "elsewhere"), folder("c4", "absent"))) {
            assertEquals(ids(FolderRows.chatsIn(f, all)), ids(FolderRows.chatsIn(f, servers)));
            assertEquals(FolderRows.unread(f, all), FolderRows.unread(f, servers));
            for (Chat s : List.of(outer, inner, quiet, elsewhere)) {
                InboxRows.Rows fresh = InboxRows.channels(all, s.id(), null, java.util.Map.of());
                assertEquals(ids(fresh.rows()), ids(servers.channels(s).rows()));
                assertEquals(fresh.unjoined(), servers.channels(s).unjoined());
                assertEquals(fresh.unread(), servers.channels(s).unread());
                assertEquals(fresh.latest(), servers.channels(s).latest());
            }
        }
    }
}
