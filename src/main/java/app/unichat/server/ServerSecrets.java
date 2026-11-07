package app.unichat.server;

import app.unichat.settings.SyncedFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Properties;

/** The local server's secrets file: its account, registration token and double-puppet token, made up the first time. */
final class ServerSecrets {
    private final Path dir;

    ServerSecrets(Path dir) {
        this.dir = dir;
    }

    /** What's saved, after creating whatever is missing. */
    Properties load() throws IOException {
        Path f = dir.resolve("secrets.properties");
        Properties p = new Properties();
        if (Files.exists(f)) {
            try (InputStream in = Files.newInputStream(f)) {
                p.load(in);
            }
        }
        boolean changed = false;
        for (String[] kv : new String[][]{{"user", LocalServer.USER}, {"password", null}, {"registration_token", null},
                {"double_puppet_token", null}}) {
            if (p.getProperty(kv[0]) == null) {
                p.setProperty(kv[0], kv[1] != null ? kv[1] : random(kv[0].equals("password") ? 24 : 48));
                changed = true;
            }
        }
        if (changed) save(p);
        return p;
    }

    /** Whole or not at all: an empty file after a crash would mean a new password for an account that already exists. */
    void save(Properties p) throws IOException {
        Files.createDirectories(dir);
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        p.store(content, "Unichat local server - private");
        SyncedFile.replace(dir.resolve("secrets.properties"), content.toByteArray(), true);
    }

    static String random(int n) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }
}
