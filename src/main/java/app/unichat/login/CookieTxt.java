package app.unichat.login;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Reads a Netscape-format cookies.txt file into a {@link SessionCookies}. */
public final class CookieTxt {
    private static final String HTTP_ONLY = "#HttpOnly_";

    private CookieTxt() {}

    /**
     * Parses {@code file}, dropping expired cookies and cookies of other networks.
     *
     * @throws IOException if the file cannot be read
     */
    public static SessionCookies read(Path file, CookieSpec spec, Instant now) throws IOException {
        long nowSec = now.getEpochSecond();
        Map<String, String> byName = new HashMap<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.strip().isEmpty() ? "" : raw.replaceAll("[\\r\\n]+$", "");
            if (line.isEmpty()) continue;
            if (line.startsWith(HTTP_ONLY)) line = line.substring(HTTP_ONLY.length());
            else if (line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 7) continue;
            String domain = f[0].strip();
            String expiryText = f[4].strip();
            long expiry = 0;
            if (!expiryText.isEmpty()) {
                try { expiry = Long.parseLong(expiryText); } catch (NumberFormatException e) { continue; }
            }
            if (expiry > 0 && expiry < nowSec) continue;
            if (!spec.isForNetwork(domain)) continue;
            byName.put(f[5].strip(), f[6].strip());
        }
        return spec.select(byName);
    }
}
