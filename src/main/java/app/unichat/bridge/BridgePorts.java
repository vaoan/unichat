package app.unichat.bridge;

import java.util.Map;

/** What the app and the local server agree on about each bridge: its port, its media name, its Telegram chat limit. */
public final class BridgePorts {
    private BridgePorts() {}

    public static final Map<String, Integer> BRIDGE_PORTS = Map.of(
            "whatsapp", 29318, "telegram", 29317, "signal", 29328, "instagram", 29319, "facebook", 29320,
            "discord", 29334, "slack", 29335, "gmessages", 29336, "twitter", 29327, "linkedin", 29341);

    /** Telegram chats created per chat sync (the bridge's default is 15); raising it triggers one resync. */
    public static final int TELEGRAM_CREATE_LIMIT = 100;

    /**
     * Server name of a bridge's direct media: its messages carry {@code mxc://media-<bridge>.localhost/...} URIs
     * that the bridge resolves from the network only when opened, so no media is copied to the server.
     * Unichat downloads them from the bridge itself (no federation; see BridgePorts.directMediaBase).
     */
    public static String mediaServerName(String bridge) {
        return "media-" + bridge + ".localhost";
    }

    /**
     * Where to download {@code mxc://<serverName>/...} when it's a bridge's direct media
     * ({@link BridgeConfig#mediaServerName}): the bridge serves the client media API on its appservice port and
     * fetches the file from the network then. Null for any other server name. Static: needs no running server.
     */
    public static String directMediaBase(String serverName) {
        for (Map.Entry<String, Integer> e : BRIDGE_PORTS.entrySet()) {
            if (mediaServerName(e.getKey()).equals(serverName)) return "http://127.0.0.1:" + e.getValue();
        }
        return null;
    }
}
