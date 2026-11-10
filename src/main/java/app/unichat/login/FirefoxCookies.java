package app.unichat.login;

import java.io.IOException;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Reads the user's own Firefox session cookies. Only runs on an explicit user action and only
 * for the network being connected. Nothing is persisted or logged; values are never printed.
 */
public final class FirefoxCookies {
    /** Optional override of the profile roots (separated by ';' or {@link java.io.File#pathSeparator}). */
    public static final String ROOTS_PROPERTY = "unichat.browserRoots";

    private FirefoxCookies() {
    }

    /** The default Firefox profiles folder for this OS. */
    public static Path profilesRoot() {
        return profilesRoot(System.getProperty("os.name", ""));
    }

    static Path profilesRoot(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        Path home = Paths.get(System.getProperty("user.home", "."));
        if (os.contains("mac")) {
            return home.resolve("Library").resolve("Application Support").resolve("Firefox").resolve("Profiles");
        }
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path base = appData != null && !appData.isBlank()
                    ? Paths.get(appData) : home.resolve("AppData").resolve("Roaming");
            return base.resolve("Mozilla").resolve("Firefox").resolve("Profiles");
        }
        return home.resolve(".mozilla").resolve("firefox");
    }

    /** Roots to scan: the {@value #ROOTS_PROPERTY} override if set, else {@link #profilesRoot()}. */
    static List<Path> roots() {
        List<Path> override = rootsOverride();
        return override != null ? override : List.of(profilesRoot());
    }

    /** Reads the network's cookies from a (non-locked) cookies.sqlite, dropping expired ones. */
    public static SessionCookies read(Path cookiesSqlite, CookieSpec spec, Instant now) {
        Map<String, String> all = new HashMap<>();
        String sql = "SELECT name, value, host, expiry FROM moz_cookies";
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + cookiesSqlite.toAbsolutePath());
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (!spec.isForNetwork(rs.getString(3))) continue;
                long expiry = rs.getLong(4);
                if (expiry > 0 && expiry < now.getEpochSecond()) continue;
                String name = rs.getString(1);
                String value = rs.getString(2);
                if (name != null && value != null) all.put(name, value);
            }
        } catch (SQLException e) {
            // Deliberately no cookie data in the message.
            throw new IllegalStateException("Could not read Firefox cookies database", e);
        }
        return spec.select(all);
    }

    /** One session per Firefox profile that has a cookies.sqlite; copies it to a temp file first. */
    public static List<BrowserSession> findSessions(CookieSpec spec) {
        List<BrowserSession> out = new ArrayList<>();
        for (Path root : roots()) {
            if (!Files.isDirectory(root)) continue;
            List<Path> profiles;
            try (Stream<Path> s = Files.list(root)) {
                profiles = s.filter(Files::isDirectory).sorted().toList();
            } catch (IOException e) {
                continue;
            }
            for (Path profile : profiles) {
                Path db = profile.resolve("cookies.sqlite");
                if (!Files.isRegularFile(db)) continue;
                SessionCookies cookies = CookieDbCopy.read(db, copy -> read(copy, spec, Instant.now()));
                if (cookies != null) {
                    out.add(new BrowserSession("Firefox", profile.getFileName().toString(), cookies));
                }
            }
        }
        return out;
    }

    /** The folders set in {@link #ROOTS_PROPERTY}, or null when it isn't set. */
    static List<Path> rootsOverride() {
        String override = System.getProperty(ROOTS_PROPERTY);
        if (override == null || override.isBlank()) return null;
        List<Path> out = new ArrayList<>();
        for (String part : override.split(";|" + java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (!part.isBlank()) out.add(Paths.get(part.trim()));
        }
        return out;
    }
}
