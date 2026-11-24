package app.unichat.matrix;

import app.unichat.service.BridgeAccess;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordGuildsTest {
    @Test
    void parsesTheBridgesGuildList() {
        List<BridgeAccess.DiscordGuild> g = BridgeProtocol.parseGuilds("""
                List of guilds:
                * FF14 Housing (`123456789012345678`) - never bridge messages
                * Amigos (con paréntesis) (`223456789012345678`) - bridge all messages and create portals on first message
                * Trabajo (`323456789012345678`) - bridge all messages and create portals proactively
                """);
        assertEquals(3, g.size());
        assertEquals(new BridgeAccess.DiscordGuild("123456789012345678", "FF14 Housing", false), g.get(0));
        assertEquals("Amigos (con paréntesis)", g.get(1).name());
        assertTrue(g.get(1).bridged());
        assertTrue(g.get(2).bridged());
    }

    @Test
    void noGuilds() {
        assertEquals(0, BridgeProtocol.parseGuilds("No guilds found").size());
    }

    private static final List<BridgeAccess.DiscordGuild> GUILDS = List.of(
            new BridgeAccess.DiscordGuild("1", "Uno", false),
            new BridgeAccess.DiscordGuild("2", "Dos", true),
            new BridgeAccess.DiscordGuild("3", "Tres", false));

    @Test
    void aServerWhoseSpaceAlreadyArrivedIsNoLongerToAdd() {
        assertEquals(List.of("3"), BridgeProtocol.offers(GUILDS, java.util.Set.of("1")).stream()
                .map(app.unichat.service.ChatService.GroupOffer::id).toList());
    }
}
