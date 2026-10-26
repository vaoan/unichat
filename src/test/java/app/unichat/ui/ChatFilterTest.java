package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Member;
import app.unichat.model.Network;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        grupo.members().add(new Member("@x", "José Pérez", null));
        List<Chat> all = List.of(mama, grupo);
        assertEquals(List.of("Mamá"), ids(ChatFilter.DEFAULT.withQuery("mama").apply(all)));
        assertEquals(List.of("Grupo"), ids(ChatFilter.DEFAULT.withQuery("jose").apply(all)));
    }
}
