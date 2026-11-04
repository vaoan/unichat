package app.unichat.service;

import app.unichat.login.BridgeLogin;
import app.unichat.model.Network;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** What a {@link ChatService} backed by mautrix bridges adds: direct access to a bridge. The demo service has none. */
public interface BridgeAccess {
    /** A Discord server ("guild") the account is in; {@code bridged}: its messages come into Unichat. */
    record DiscordGuild(String id, String name, boolean bridged) {}

    /**
     * The bridge's own login API for {@code n} (dynamic login: the bridge says which methods and steps it has),
     * or null when only the bot chat can be used (external servers). Call {@link #prepareBridge} first.
     */
    BridgeLogin bridgeLogin(Network n);

    /** Installs and starts {@code n}'s bridge when the local server provides it. Blocking. */
    void prepareBridge(Network n) throws Exception;

    /** After a login through the bridge's API: refresh that network's accounts. */
    void loginFinished(Network n);

    /** The Discord servers of the signed-in account, asked to the bridge bot. */
    CompletableFuture<List<DiscordGuild>> discordGuilds();

    /**
     * Bridges a Discord server (its channels appear as people write in them) or stops bridging it. Not {@code
     * --entire}: that would create a room for every channel of the server at once.
     */
    CompletableFuture<Void> setDiscordGuildBridged(String guildId, boolean bridged);
}
