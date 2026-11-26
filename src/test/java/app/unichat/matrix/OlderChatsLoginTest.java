package app.unichat.matrix;

import app.unichat.model.Network;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static app.unichat.matrix.BridgeProtocol.Login;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * "Nothing more" belongs to an account: a new login on the network has its own older chats to ask for. The account it
 * belonged to is kept in the settings, so a different one after a restart is seen too.
 */
class OlderChatsLoginTest {
    private final Map<String, String> settings = new HashMap<>(Map.of(
            "olderChats.whatsapp.done", "true", "olderChats.whatsapp.groups", "done", "olderChats.whatsapp.login", "573001",
            "olderChats.instagram.done", "true", "olderChats.instagram.login", "ig0",
            "olderChats.telegram.done", "true", "olderChats.telegram.extra", "200", "olderChats.telegram.login", "9000"));

    private @Nullable String get(String key) { return settings.get(key); }

    private void set(String key, @Nullable String value) {
        if (value == null) settings.remove(key);
        else settings.put(key, value);
    }

    private void logins(Network n, List<Login> now) {
        BridgeAccounts.forgetOlderChatsOnNewLogin(n, now, this::get, this::set);
    }

    private static List<Login> login(String... ids) {
        return java.util.Arrays.stream(ids).map(id -> new Login(id, "Ana", "CONNECTED")).toList();
    }

    @Test
    void aNewLoginClearsNothingMore() {
        logins(Network.WHATSAPP, login("573002"));
        logins(Network.INSTAGRAM, login("ig1"));
        logins(Network.TELEGRAM, login("9000"));
        assertEquals(Map.of("olderChats.whatsapp.login", "573002", "olderChats.instagram.login", "ig1",
                "olderChats.telegram.done", "true", "olderChats.telegram.extra", "200", "olderChats.telegram.login", "9000"), settings,
                "WhatsApp's done and groups and Instagram's done go, also after a restart (the account kept is another);"
                        + " Telegram, the same account, keeps its own");
    }

    @Test
    void theSameLoginKeepsNothingMore() {
        logins(Network.WHATSAPP, login("573001"));
        logins(Network.INSTAGRAM, List.of());
        logins(Network.LINKEDIN, login("li1"));
        Map<String, String> expected = new HashMap<>(settings);
        assertEquals("li1", expected.remove("olderChats.linkedin.login"), "an account not known before is kept from now on");
        Map<String, String> before = Map.of(
                "olderChats.whatsapp.done", "true", "olderChats.whatsapp.groups", "done", "olderChats.whatsapp.login", "573001",
                "olderChats.instagram.done", "true", "olderChats.instagram.login", "ig0",
                "olderChats.telegram.done", "true", "olderChats.telegram.extra", "200", "olderChats.telegram.login", "9000");
        assertEquals(before, expected, "the same login, a logout, or a first one keep everything");
    }

    @Test
    void theSameAccountsInAnotherOrderAreTheSame() {
        settings.put("olderChats.whatsapp.login", "a,b");
        logins(Network.WHATSAPP, login("b", "a"));
        assertEquals("true", settings.get("olderChats.whatsapp.done"));
    }
}
