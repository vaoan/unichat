package app.unichat.matrix;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscordGuildsTest {
    @Test
    void parsesTheBridgesGuildList() {
        List<MatrixChatService.DiscordGuild> g = MatrixChatService.parseGuilds("""
                List of guilds:
                * FF14 Housing (`123456789012345678`) - never bridge messages
                * Amigos (con paréntesis) (`223456789012345678`) - bridge all messages and create portals on first message
                * Trabajo (`323456789012345678`) - bridge all messages and create portals proactively
                """);
        assertEquals(3, g.size());
        assertEquals(new MatrixChatService.DiscordGuild("123456789012345678", "FF14 Housing", false), g.get(0));
        assertEquals("Amigos (con paréntesis)", g.get(1).name());
        assertEquals(true, g.get(1).bridged());
        assertEquals(true, g.get(2).bridged());
    }

    @Test
    void noGuilds() {
        assertEquals(0, MatrixChatService.parseGuilds("No guilds found").size());
    }

    private static final List<MatrixChatService.DiscordGuild> GUILDS = List.of(
            new MatrixChatService.DiscordGuild("1", "Uno", false),
            new MatrixChatService.DiscordGuild("2", "Dos", true),
            new MatrixChatService.DiscordGuild("3", "Tres", false));

    @Test
    void everyServerComesInLikeTelegramGroupsExceptTheOnesTurnedOff() {
        assertEquals(List.of("1", "3"), MatrixChatService.guildsToBridge(GUILDS, java.util.Set.of()));
        assertEquals(List.of("3"), MatrixChatService.guildsToBridge(GUILDS, java.util.Set.of("1")),
                "a server switched off in Cuentas stays off");
    }

    @Test
    void aServerWhoseSpaceAlreadyArrivedIsNoLongerToAdd() {
        assertEquals(List.of("3"), MatrixChatService.offers(GUILDS, java.util.Set.of("1")).stream()
                .map(app.unichat.service.ChatService.GroupOffer::id).toList());
    }

    @Test
    void switchingAServerOffIsRemembered() {
        app.unichat.settings.Settings.get().set(MatrixChatService.UNWANTED_GUILDS_KEY, null);
        MatrixChatService.rememberGuildChoice("7", false);
        MatrixChatService.rememberGuildChoice("8", false);
        MatrixChatService.rememberGuildChoice("7", true);
        assertEquals(java.util.Set.of("8"), MatrixChatService.unwantedGuilds());
        app.unichat.settings.Settings.get().set(MatrixChatService.UNWANTED_GUILDS_KEY, null);
    }
}
