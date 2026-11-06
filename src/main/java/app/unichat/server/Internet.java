package app.unichat.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/** Whether this computer reaches the internet: opens a connection (and sends nothing) to sites the bridges use anyway. */
final class Internet {
    private static final List<String> HOSTS = List.of("discord.com", "web.whatsapp.com", "telegram.org");
    private static final int TIMEOUT_MS = 3_000;

    private Internet() {}

    static boolean reachable() {
        for (String host : HOSTS) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, 443), TIMEOUT_MS);
                return true;
            } catch (IOException e) {
                // Try the next one: a single site being down isn't the internet being down.
            }
        }
        return false;
    }
}
