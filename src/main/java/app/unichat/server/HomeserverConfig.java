package app.unichat.server;

import app.unichat.settings.SyncedFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** tuwunel.toml for the personal server, rewritten on every start (nothing in it is edited by hand), and the double-puppet registration. */
final class HomeserverConfig {
    private HomeserverConfig() {}

    /** {@code dataDir}/tuwunel/tuwunel.toml, only the person's: the registration token in it creates accounts. */
    static void write(Path dataDir, int port, String domain, String registrationToken) throws IOException {
        Path hs = dataDir.resolve("tuwunel");
        Files.createDirectories(hs.resolve("db"));
        SyncedFile.replace(hs.resolve("tuwunel.toml"), toml(dataDir, port, domain, registrationToken).getBytes(StandardCharsets.UTF_8), true);
    }

    /**
     * Lets bridges send what you write on your phone as you (written once, tokens must stay stable). Only the person's: its
     * as_token speaks as anyone on the server. One an older Unichat left readable is closed, tokens unchanged.
     */
    static void writeDoublePuppet(Path dataDir, String asToken, String domain) throws IOException {
        Path f = dataDir.resolve("appservices").resolve("doublepuppet.yaml");
        byte[] content = Files.exists(f) ? Files.readAllBytes(f) : """
                id: doublepuppet
                url:
                as_token: "%s"
                hs_token: "%s"
                sender_localpart: doublepuppet
                rate_limited: false
                namespaces:
                  users:
                    - regex: '@.*:%s'
                      exclusive: false
                """.formatted(asToken, ServerSecrets.random(48), domain).getBytes(StandardCharsets.UTF_8);
        SyncedFile.replace(f, content, true);
    }

    static String toml(Path dataDir, int port, String domain, String registrationToken) {
        return """
                # Written by Unichat on every start; edits are overwritten.
                [global]
                server_name = %s
                database_path = %s
                address = ["127.0.0.1"]
                port = %d
                # Personal server: no federation, no presence (the main background load with many bridged contacts).
                allow_federation = false
                allow_local_presence = false
                allow_incoming_presence = false
                allow_outgoing_presence = false
                # Fixed resolvers: tuwunel won't start when /etc/resolv.conf lists a scoped IPv6 nameserver (macOS
                # adds fe80::1 on en0 on some networks); without federation it barely resolves anything anyway.
                dns_servers = ["1.1.1.1", "9.9.9.9"]
                # Only Unichat (which knows the token) creates accounts; the first one becomes server admin.
                allow_registration = true
                registration_token = %s
                new_user_displayname_suffix = ""
                appservice_dir = %s
                max_request_size = 104857600
                # Console commands passed at start (old Discord media cleanup) never stop the server if they fail.
                admin_execute_errors_ignore = true
                log = "info"
                """.formatted(str(domain), str(dataDir.resolve("tuwunel").resolve("db").toString()), port,
                str(registrationToken), str(dataDir.resolve("appservices").toString()));
    }

    /** TOML basic string. */
    private static String str(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
