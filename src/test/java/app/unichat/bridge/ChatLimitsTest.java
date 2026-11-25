package app.unichat.bridge;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatLimitsTest {
    @Test
    void readsWhatWasSaved() {
        Map<String, String> saved = Map.of("olderChats.telegram.extra", "200", "olderChats.discord.extra", "100",
                "olderChats.linkedin.all", "true", "olderChats.gmessages.once", "true", "olderChats.instagram.once", "true");
        ChatLimits l = ChatLimits.parse(saved::get);
        assertEquals(new ChatLimits(200, 100, true, true, true), l);
        assertEquals(BridgePorts.TELEGRAM_CREATE_LIMIT + 200, l.telegramLimit());
        assertEquals(ChatLimits.DISCORD_BASE + 100, l.discordLimit());
    }

    @Test
    void nothingSavedIsNone() {
        Map<String, String> none = Map.of();
        assertEquals(ChatLimits.NONE, ChatLimits.parse(none::get));
        assertEquals(BridgePorts.TELEGRAM_CREATE_LIMIT, ChatLimits.NONE.telegramLimit());
        assertEquals(ChatLimits.DISCORD_BASE, ChatLimits.NONE.discordLimit());
    }

    @Test
    void garbageOrNegativeIsTheDefault() {
        Map<String, String> garbage = Map.of("olderChats.telegram.extra", "abc", "olderChats.discord.extra", "-5",
                "olderChats.linkedin.all", "maybe", "olderChats.gmessages.once", "maybe", "olderChats.instagram.once", "maybe");
        assertEquals(ChatLimits.NONE, ChatLimits.parse(garbage::get));
    }
}
