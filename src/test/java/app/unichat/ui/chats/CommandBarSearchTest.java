package app.unichat.ui.chats;

import app.unichat.model.Chat;
import app.unichat.model.Network;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandBarSearchTest {
    @Test
    void spacesAreNotOfferedAsChats() {
        Chat server = new Chat("!s");
        server.setTitle("Servidor Unichat");
        server.setNetwork(Network.DISCORD);
        server.setSpace(true);
        Chat channel = new Chat("!c");
        channel.setTitle("Servidor general");
        channel.setNetwork(Network.DISCORD);
        assertEquals(List.of("!c"), CommandBar.chatsFor(List.of(server, channel), "serv").stream().map(Chat::id).toList());
        assertEquals(List.of("!c"), CommandBar.chatsFor(List.of(server, channel), "").stream().map(Chat::id).toList());
    }
}
