package app.unichat.matrix;

import app.unichat.model.Network;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static app.unichat.matrix.BridgeProtocol.Login;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** "Nothing more" belongs to an account: a new login on the network has its own older chats to ask for. */
class OlderChatsLoginTest {
    private final Map<String, String> settings = new HashMap<>(Map.of(
            "olderChats.whatsapp.done", "true", "olderChats.whatsapp.groups", "done",
            "olderChats.instagram.done", "true", "olderChats.telegram.done", "true", "olderChats.telegram.extra", "200"));

    private void set(String key, @Nullable String value) {
        if (value == null) settings.remove(key);
        else settings.put(key, value);
    }

    private static List<Login> login(String id) {
        return List.of(new Login(id, "Ana", "CONNECTED"));
    }

    @Test
    void aNewLoginClearsNothingMore() {
        BridgeAccounts.forgetOlderChatsOnNewLogin(Network.WHATSAPP, login("573001"), login("573002"), this::set);
        BridgeAccounts.forgetOlderChatsOnNewLogin(Network.INSTAGRAM, List.of(), login("ig1"), this::set);
        assertEquals(Map.of("olderChats.telegram.done", "true", "olderChats.telegram.extra", "200"), settings,
                "WhatsApp's done and groups and Instagram's done go; Telegram, whose login didn't change, keeps its own");
    }

    @Test
    void theSameLoginKeepsNothingMore() {
        Map<String, String> before = Map.copyOf(settings);
        BridgeAccounts.forgetOlderChatsOnNewLogin(Network.WHATSAPP, login("573001"), login("573001"), this::set);
        BridgeAccounts.forgetOlderChatsOnNewLogin(Network.TELEGRAM, null, login("9000"), this::set);
        BridgeAccounts.forgetOlderChatsOnNewLogin(Network.INSTAGRAM, login("ig1"), List.of(), this::set);
        assertEquals(before, settings, "the same login, logins not known before, or a logout keep everything");
    }
}
