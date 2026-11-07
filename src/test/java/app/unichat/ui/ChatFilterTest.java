package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Member;
import app.unichat.model.Network;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ChatFilterTest {
    private static Chat chat(String id, Network n, long t) {
        Chat c = new Chat(id);
        c.setTitle(id);
        c.setNetwork(n);
        c.setLastActivity(Instant.ofEpochSecond(t));
        return c;
    }

    private static List<String> ids(List<Chat> chats) {
        return chats.stream().map(Chat::id).toList();
    }

    @Test
    void inboxHidesArchivedLowPriorityAndBridgeBotsAndSortsPinnedThenRecent() {
        Chat a = chat("a", Network.WHATSAPP, 10);
        Chat b = chat("b", Network.TELEGRAM, 30);
        Chat pinned = chat("pinned", Network.SIGNAL, 1);
        pinned.setPinned(true);
        Chat archived = chat("archived", Network.WHATSAPP, 50);
        archived.setArchived(true);
        Chat low = chat("low", Network.WHATSAPP, 40);
        low.setLowPriority(true);
        Chat bot = chat("bot", Network.WHATSAPP, 60);
        bot.setBridgeBot(true);
        List<Chat> all = List.of(a, b, pinned, archived, low, bot);

        assertEquals(List.of("pinned", "b", "a"), ids(ChatFilter.DEFAULT.apply(all)));
        assertEquals(List.of("archived"), ids(ChatFilter.DEFAULT.withView(ChatFilter.View.ARCHIVE).apply(all)));
        assertEquals(List.of("low"), ids(ChatFilter.DEFAULT.withView(ChatFilter.View.LOW_PRIORITY).apply(all)));
    }

    @Test
    void networkAndUnreadFilters() {
        Chat a = chat("a", Network.WHATSAPP, 10);
        a.setUnreadCount(2);
        Chat b = chat("b", Network.WHATSAPP, 20);
        Chat c = chat("c", Network.TELEGRAM, 30);
        c.setMarkedUnread(true);
        List<Chat> all = List.of(a, b, c);
        assertEquals(List.of("b", "a"), ids(ChatFilter.DEFAULT.withNetwork(Network.WHATSAPP).apply(all)));
        assertEquals(List.of("c", "a"), ids(ChatFilter.DEFAULT.withUnreadOnly(true).apply(all)));
    }

    @Test
    void accountFilterKeepsOnlyThatLogin() {
        Chat personal = chat("p", Network.WHATSAPP, 10);
        personal.setAccountId("whatsapp:1");
        Chat work = chat("w", Network.WHATSAPP, 20);
        work.setAccountId("whatsapp:2");
        Chat tg = chat("t", Network.TELEGRAM, 30);
        List<Chat> all = List.of(personal, work, tg);
        assertEquals(List.of("w"), ids(ChatFilter.DEFAULT.withAccount(Network.WHATSAPP, "whatsapp:2").apply(all)));
        assertEquals(List.of("w", "p"), ids(ChatFilter.DEFAULT.withAccount(Network.WHATSAPP, "whatsapp:2")
                .withNetwork(Network.WHATSAPP).apply(all)), "choosing the network again shows every account");
    }

    @Test
    void searchIsAccentInsensitiveIncludesArchivedAndMembers() {
        Chat mama = chat("Mamá", Network.WHATSAPP, 10);
        mama.setArchived(true);
        Chat grupo = chat("Grupo", Network.TELEGRAM, 20);
        grupo.setMembers(java.util.List.of(new Member("@x", "José Pérez", null)));
        List<Chat> all = List.of(mama, grupo);
        assertEquals(List.of("Mamá"), ids(ChatFilter.DEFAULT.withQuery("mama").apply(all)));
        assertEquals(List.of("Grupo"), ids(ChatFilter.DEFAULT.withQuery("jose").apply(all)));
    }

    @Test
    void inboxBadgeCountMatchesNoLeidosFilterForEveryFlagMix() {
        // The bug: badge said "1" but "No leídos" was empty. Badge and the filter must count the same chats.
        Chat plain = chat("plain", Network.WHATSAPP, 10); plain.setUnreadCount(1);
        Chat low = chat("low", Network.WHATSAPP, 20); low.setUnreadCount(1); low.setLowPriority(true);
        Chat muted = chat("muted", Network.WHATSAPP, 30); muted.setUnreadCount(1); muted.setMuted(true);
        Chat archived = chat("archived", Network.WHATSAPP, 40); archived.setUnreadCount(1); archived.setArchived(true);
        Chat marked = chat("marked", Network.TELEGRAM, 50); marked.setMarkedUnread(true);
        Chat read = chat("read", Network.WHATSAPP, 60);
        List<Chat> all = List.of(plain, low, muted, archived, marked, read);

        long badge = all.stream().filter(ChatFilter::inboxUnread).count();
        List<String> noLeidos = ids(ChatFilter.DEFAULT.withUnreadOnly(true).apply(all));
        assertEquals(badge, noLeidos.size(), "badge count must equal the No leídos list size");
        assertEquals(List.of("marked", "plain"), noLeidos, "plain + marked-unread; low/muted/archived excluded");
    }

    @Test
    void inboxUnreadExcludesLowPriorityMutedArchivedAndBots() {
        Chat low = chat("low", Network.WHATSAPP, 10); low.setUnreadCount(1); low.setLowPriority(true);
        Chat muted = chat("muted", Network.WHATSAPP, 10); muted.setUnreadCount(1); muted.setMuted(true);
        Chat bot = chat("bot", Network.WHATSAPP, 10); bot.setUnreadCount(1); bot.setBridgeBot(true);
        Chat ok = chat("ok", Network.WHATSAPP, 10); ok.setUnreadCount(1);
        assertFalse(ChatFilter.inboxUnread(low));
        assertFalse(ChatFilter.inboxUnread(muted));
        assertFalse(ChatFilter.inboxUnread(bot));
        assertTrue(ChatFilter.inboxUnread(ok));
    }

    @Test
    void switchingNetworkKeepsTheChosenChip() {
        ChatFilter fav = ChatFilter.DEFAULT.withView(ChatFilter.View.FAVORITES).switchTo(Network.WHATSAPP, null);
        assertEquals(ChatFilter.View.FAVORITES, fav.view());
        assertEquals(Network.WHATSAPP, fav.network());
        ChatFilter low = ChatFilter.DEFAULT.withView(ChatFilter.View.LOW_PRIORITY).switchTo(Network.TELEGRAM, "telegram:1");
        assertEquals(ChatFilter.View.LOW_PRIORITY, low.view());
        assertEquals("telegram:1", low.accountId());
        ChatFilter unread = ChatFilter.DEFAULT.withUnreadOnly(true).switchTo(null, null);
        assertTrue(unread.unreadOnly());
    }

    @Test
    void leavingTheArchiveGoesBackToTheInbox() {
        ChatFilter f = ChatFilter.DEFAULT.withView(ChatFilter.View.ARCHIVE).switchTo(Network.SIGNAL, null);
        assertEquals(ChatFilter.View.INBOX, f.view());
    }

    @Test
    void spacesNeverShowInAnyView() {
        Chat s = chat("!s", Network.DISCORD, 100);
        s.setTitle("Servidor Unichat");
        s.setSpace(true);
        s.setUnreadCount(3);
        s.setFavorite(true);
        for (ChatFilter f : List.of(ChatFilter.DEFAULT, ChatFilter.DEFAULT.withView(ChatFilter.View.FAVORITES),
                ChatFilter.DEFAULT.withView(ChatFilter.View.ARCHIVE), ChatFilter.DEFAULT.withQuery("servidor"))) {
            assertFalse(f.matches(s), f.toString());
        }
    }

    @Test
    void spacesNeverCountAsUnread() {
        Chat s = chat("!s", Network.DISCORD, 100);
        s.setSpace(true);
        s.setUnreadCount(3);
        assertFalse(ChatFilter.inboxUnread(s));
    }
}
