package app.unichat.ui.chats;

import app.unichat.model.Chat;
import app.unichat.model.Network;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Servers/groups live in the inbox too: a row ordered by their channels' activity, opened in place. */
class InboxRowsTest {
    private final List<Chat> chats = new ArrayList<>();

    private Chat chat(String id, Network n, long at) {
        Chat c = new Chat(id);
        c.setTitle(id.substring(1));
        c.setNetwork(n);
        c.setLastActivity(Instant.ofEpochSecond(at));
        chats.add(c);
        return c;
    }

    private Chat space(String id, Network n, String... children) {
        Chat s = chat(id, n, 0);
        s.setSpace(true);
        s.setSpaceChildren(List.of(children));
        return s;
    }

    private void fill() {
        space("!srv", Network.DISCORD, "!general", "!memes", "!unjoined");
        chat("!general", Network.DISCORD, 50).setUnreadCount(2);
        chat("!memes", Network.DISCORD, 10);
        chat("!mama", Network.WHATSAPP, 40);
        chat("!pedro", Network.TELEGRAM, 60);
    }

    private static List<String> ids(InboxRows.Rows r) {
        return r.rows().stream().map(Chat::id).toList();
    }

    @Test
    void aServerIsOneRowAndItsChannelsLiveInsideIt() {
        fill();
        InboxRows.Rows r = InboxRows.build(chats, ChatFilter.DEFAULT, Map.of());
        assertEquals(List.of("!pedro", "!srv", "!mama"), ids(r), "like a Telegram forum: the server sums up its channels");
        assertEquals(2, r.unread().get("!srv"));
        assertEquals("!general", r.latest().get("!srv").id());
    }

    @Test
    void searchingStillFindsChannels() {
        fill();
        assertEquals(List.of("!general"), ids(InboxRows.build(chats, ChatFilter.DEFAULT.withQuery("gener"), Map.of())));
    }

    @Test
    void aServerOpensOnItsChannelsWithTheOnesToJoin() {
        fill();
        InboxRows.Rows r = InboxRows.channels(chats, "!srv", null, Map.of("!unjoined", "#anuncios"));
        assertEquals(List.of("!general", "!memes", "!unjoined"), ids(r));
        assertTrue(r.unjoined().contains("!unjoined"));
        assertEquals("#anuncios", r.rows().get(2).title());
    }

    @Test
    void anArchivedChannelLeavesItsServerAndGoesToTheArchive() {
        fill();
        chats.stream().filter(c -> c.id().equals("!memes")).findFirst().orElseThrow().setArchived(true);
        assertTrue(ids(InboxRows.build(chats, ChatFilter.DEFAULT.withView(ChatFilter.View.ARCHIVE), Map.of())).contains("!memes"));
        assertFalse(ids(InboxRows.channels(chats, "!srv", null, Map.of())).contains("!memes"));
        assertTrue(ids(InboxRows.build(chats, ChatFilter.DEFAULT, Map.of())).contains("!srv"), "the server stays out");
    }

    @Test
    void filtersApplyToServersToo() {
        fill();
        chats.get(0).setFavorite(true);
        assertEquals(List.of("!srv"), ids(InboxRows.build(chats, ChatFilter.DEFAULT.withView(ChatFilter.View.FAVORITES), Map.of())));
        assertEquals(List.of("!mama"), ids(InboxRows.build(chats, ChatFilter.DEFAULT.withNetwork(Network.WHATSAPP), Map.of())));
        List<String> unread = ids(InboxRows.build(chats, ChatFilter.DEFAULT.withUnreadOnly(true), Map.of()));
        assertEquals(List.of("!srv"), unread, "a server with unread channels counts as unread");
        assertEquals(List.of("!srv"), ids(InboxRows.build(chats, ChatFilter.DEFAULT.withQuery("sr"), Map.of())));
        chats.get(0).setArchived(true);
        assertFalse(ids(InboxRows.build(chats, ChatFilter.DEFAULT, Map.of())).contains("!srv"));
        assertTrue(ids(InboxRows.build(chats, ChatFilter.DEFAULT.withView(ChatFilter.View.ARCHIVE), Map.of())).contains("!srv"));
    }

    @Test
    void theBridgesPersonalFolderIsNotAServer() {
        fill();
        Chat folder = space("!folder", Network.TELEGRAM, "!pedro");
        folder.setPersonalSpace(true);
        assertFalse(ids(InboxRows.build(chats, ChatFilter.DEFAULT, Map.of())).contains("!folder"));
    }
}
