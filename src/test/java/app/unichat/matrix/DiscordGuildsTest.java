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
}
