package app.unichat.server;

import java.nio.file.Path;

/** tuwunel.toml for the personal server; rewritten on every start (nothing in it is edited by hand). */
final class HomeserverConfig {
    private HomeserverConfig() {}

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
                # Only Unichat (which knows the token) creates accounts; the first one becomes server admin.
                allow_registration = true
                registration_token = %s
                new_user_displayname_suffix = ""
                appservice_dir = %s
                max_request_size = 104857600
                log = "info"
                """.formatted(str(domain), str(dataDir.resolve("tuwunel").resolve("db").toString()), port,
                str(registrationToken), str(dataDir.resolve("appservices").toString()));
    }

    /** TOML basic string. */
    private static String str(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
