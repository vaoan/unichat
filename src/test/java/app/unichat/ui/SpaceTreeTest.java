package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Network;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SpaceTreeTest {
    private final List<Chat> chats = new ArrayList<>();

    private Chat chat(String id, Network n) {
        Chat c = new Chat(id);
        c.setTitle(id.substring(1));
        c.setNetwork(n);
        c.setLastActivity(Instant.ofEpochSecond(chats.size()));
        chats.add(c);
        return c;
    }

    private Chat space(String id, Network n, String... children) {
        Chat s = chat(id, n);
        s.setSpace(true);
        s.setSpaceChildren(List.of(children));
        return s;
    }

    private static List<String> names(List<SpaceTree.Node> nodes) {
        return nodes.stream().map(SpaceTree.Node::name).toList();
    }

    @Test
    void serversListTheirChannels() {
        space("!srv", Network.DISCORD, "!c1", "!c2");
        chat("!c1", Network.DISCORD);
        chat("!c2", Network.DISCORD);
        List<SpaceTree.Node> roots = SpaceTree.build(chats, Network.DISCORD, Map.of());
        assertEquals(List.of("srv"), names(roots));
        assertEquals(List.of("c1", "c2"), names(roots.get(0).children()));
    }

    @Test
    void personalFolderIsNotAGroup() {
        space("!p", Network.TELEGRAM, "!d1", "!grp").setPersonalSpace(true);
        space("!grp", Network.TELEGRAM, "!t1");
        chat("!d1", Network.TELEGRAM);
        chat("!t1", Network.TELEGRAM);
        List<SpaceTree.Node> roots = SpaceTree.build(chats, Network.TELEGRAM, Map.of());
        assertEquals(List.of("grp", SpaceTree.LOOSE), names(roots));
        assertEquals(List.of("d1"), names(roots.get(1).children()));
    }

    @Test
    void chatsWithoutASpaceGoToLooseAndLooseIsOmittedWhenEmpty() {
        space("!srv", Network.DISCORD, "!c1");
        chat("!c1", Network.DISCORD);
        assertEquals(List.of("srv"), names(SpaceTree.build(chats, Network.DISCORD, Map.of())));
        chat("!d2", Network.DISCORD);
        assertEquals(List.of("srv", SpaceTree.LOOSE), names(SpaceTree.build(chats, Network.DISCORD, Map.of())));
    }

    @Test
    void nestedSpacesNestAndCyclesStop() {
        space("!a", Network.DISCORD, "!b");
        space("!b", Network.DISCORD, "!a", "!c");
        chat("!c", Network.DISCORD);
        List<SpaceTree.Node> roots = SpaceTree.build(chats, Network.DISCORD, Map.of());
        // a and b are each other's child: both stay roots, each shows the other once with no loop back.
        SpaceTree.Node a = roots.stream().filter(n -> n.id().equals("!a")).findFirst().orElseThrow();
        assertEquals(List.of("b"), names(a.children()));
        assertEquals(List.of("c"), names(a.children().get(0).children()));
    }

    @Test
    void childAlsoFoundThroughItsParentEvent() {
        space("!srv", Network.DISCORD);
        chat("!c1", Network.DISCORD).setSpaceParents(Set.of("!srv"));
        assertEquals(List.of("c1"), names(SpaceTree.build(chats, Network.DISCORD, Map.of()).get(0).children()));
    }

    @Test
    void unjoinedChildrenAppearWithTheirHierarchyName() {
        space("!srv", Network.DISCORD, "!x");
        SpaceTree.Node x = SpaceTree.build(chats, Network.DISCORD, Map.of("!x", "anuncios")).get(0).children().get(0);
        assertNull(x.chat());
        assertEquals("anuncios", x.name());
    }

    @Test
    void unreadSumsInboxUnreadChildren() {
        space("!srv", Network.DISCORD, "!a", "!m", "!z");
        chat("!a", Network.DISCORD).setUnreadCount(2);
        Chat m = chat("!m", Network.DISCORD);
        m.setUnreadCount(5);
        m.setMuted(true);
        Chat z = chat("!z", Network.DISCORD);
        z.setUnreadCount(1);
        z.setArchived(true);
        SpaceTree.Node srv = SpaceTree.build(chats, Network.DISCORD, Map.of()).get(0);
        assertEquals(2, SpaceTree.unread(srv));
        assertEquals(List.of("a", "m"), names(srv.children()), "archived children are left out");
    }

    @Test
    void onlyTheChosenNetworkAndNoBridgeBots() {
        space("!srv", Network.DISCORD, "!c1");
        chat("!c1", Network.DISCORD);
        chat("!w", Network.WHATSAPP);
        chat("!bot", Network.DISCORD).setBridgeBot(true);
        assertEquals(List.of("srv"), names(SpaceTree.build(chats, Network.DISCORD, Map.of())));
    }

    @Test
    void accountFilterKeepsOnlyThatAccountsGroups() {
        space("!work", Network.TELEGRAM, "!w1");
        chat("!w1", Network.TELEGRAM).setAccountId("telegram:work");
        space("!home", Network.TELEGRAM, "!h1");
        chat("!h1", Network.TELEGRAM).setAccountId("telegram:home");
        chat("!h2", Network.TELEGRAM).setAccountId("telegram:home");
        assertEquals(List.of("work"), names(SpaceTree.build(chats, Network.TELEGRAM, "telegram:work", Map.of())));
        assertEquals(List.of("home", SpaceTree.LOOSE), names(SpaceTree.build(chats, Network.TELEGRAM, "telegram:home", Map.of())));
        assertEquals(List.of("home", "work", SpaceTree.LOOSE), names(SpaceTree.build(chats, Network.TELEGRAM, null, Map.of())));
    }

    @Test
    void aChannelKnowsItsServer() {
        Chat bridgeFolder = space("!discord", Network.DISCORD, "!srv");
        bridgeFolder.setPersonalSpace(true);
        Chat srv = space("!srv", Network.DISCORD, "!c1");
        Chat c1 = chat("!c1", Network.DISCORD);
        Chat c2 = chat("!c2", Network.DISCORD);
        c2.setSpaceParents(Set.of("!srv"));
        Chat dm = chat("!dm", Network.DISCORD);
        Chat otherNet = chat("!tg", Network.TELEGRAM);
        otherNet.setSpaceParents(Set.of("!srv"));

        assertEquals(srv, SpaceTree.serverOf(c1, chats), "the server lists it");
        assertEquals(srv, SpaceTree.serverOf(c2, chats), "it names its server");
        assertNull(SpaceTree.serverOf(dm, chats), "a DM has none (the bridge's folder doesn't count)");
        assertNull(SpaceTree.serverOf(srv, chats), "a server isn't a channel");
        assertNull(SpaceTree.serverOf(otherNet, chats));
    }
}
