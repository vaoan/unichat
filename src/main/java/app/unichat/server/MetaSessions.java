package app.unichat.server;

import app.unichat.bridge.BridgeStates;
import app.unichat.support.SafeFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Keeps Meta sessions alive through something that isn't their fault. On some networks (Claro's, in Colombia)
 * www.facebook.com redirects to web.facebook.com. mautrix-meta sends its cookies as a plain header, which Go drops when a
 * redirect leaves the host's subdomains, so Facebook answers with its login page; the bridge concludes the session was
 * closed and erases the cookies (pkg/connector/client.go), for good, even once the network is fine again.
 *
 * <p>So: a private copy of the cookies is kept while they exist; no restart while the redirect is on, since a restart is
 * what makes the bridge load that page; and cookies erased from a login that's still there are put back, a limited number
 * of times, because Facebook itself rejects them if the session really was closed. A login removed on purpose has no row
 * any more, and nothing comes back.
 */
final class MetaSessions implements Watchdog.SessionKeeper {
    /** Their pages, where the bridges start from. Instagram has been seen with the same mechanism, not the same redirect. */
    private static final Map<String, URI> PAGES = Map.of(
            "facebook", URI.create("https://www.facebook.com/messages"),
            "instagram", URI.create("https://www.instagram.com/direct/inbox/"));
    static final long BACKUP_EVERY_MS = 5 * 60_000;
    static final long RETRY_AFTER_MS = 60 * 60_000;
    static final int MAX_ATTEMPTS = 2;
    private static final ObjectMapper JSON = new ObjectMapper();
    /** One for every check, never following a redirect: the redirect is what's looked at. */
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(8)).build();

    private final Path dir;
    private final BridgeStates states;
    private final Predicate<String> redirecting;
    private final Consumer<String> restoreAndRestart;
    private final Map<String, Long> lastBackup = new HashMap<>();
    private final Map<String, Integer> attempts = new HashMap<>();
    private final Map<String, Long> lastAttempt = new HashMap<>();

    /**
     * @param redirecting       whether a bridge's network is doing the redirect now
     * @param restoreAndRestart stops the bridge, calls {@link #restore} and starts it
     */
    MetaSessions(Path dir, BridgeStates states, Predicate<String> redirecting, Consumer<String> restoreAndRestart) {
        this.dir = dir;
        this.states = states;
        this.redirecting = redirecting;
        this.restoreAndRestart = restoreAndRestart;
    }

    /** The real check for {@code bridge}. */
    static boolean redirecting(String bridge) {
        URI page = PAGES.get(bridge);
        return page != null && redirectsElsewhere(page);
    }

    /** Whether {@code page} sends you to another host; not being able to ask counts as yes: nothing is lost by waiting. */
    static boolean redirectsElsewhere(URI page) {
        try {
            HttpResponse<Void> r = HTTP.send(HttpRequest.newBuilder(page).timeout(Duration.ofSeconds(12)).build(),
                    HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() / 100 != 3) return false;
            URI to = page.resolve(r.headers().firstValue("location").orElse(""));
            return !page.getHost().equalsIgnoreCase(to.getHost());
        } catch (IOException | IllegalArgumentException e) {
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    @Override
    public boolean fragile(String bridge) { return PAGES.containsKey(bridge); }

    @Override
    public boolean safeToRestart(String bridge) { return !fragile(bridge) || !redirecting.test(bridge); }

    @Override
    public void tick(boolean online, long now) {
        for (String bridge : PAGES.keySet()) {
            try {
                if (!Files.isRegularFile(database(bridge))) continue;
                Map<String, ObjectNode> logins = logins(bridge);
                keepCopy(bridge, logins, now);
                heal(bridge, logins, online, now);
            } catch (IOException | SQLException e) {
                // Never the contents: these are credentials.
                System.err.println("Meta session check failed for " + bridge + ": " + e.getClass().getSimpleName());
            }
        }
    }

    private void keepCopy(String bridge, Map<String, ObjectNode> logins, long now) throws IOException {
        Long last = lastBackup.get(bridge);
        if (last != null && now - last < BACKUP_EVERY_MS) return;
        lastBackup.put(bridge, now);
        Map<String, JsonNode> old = backup(bridge);
        Map<String, JsonNode> keep = new LinkedHashMap<>();
        for (Map.Entry<String, ObjectNode> e : logins.entrySet()) {
            if (hasCookies(e.getValue())) keep.put(e.getKey(), e.getValue().get("cookies"));
            // Erased but still listed: the copy is what can bring it back. A login no longer listed is simply dropped.
            else if (old.containsKey(e.getKey())) keep.put(e.getKey(), old.get(e.getKey()));
        }
        write(bridge, keep);
    }

    private void heal(String bridge, Map<String, ObjectNode> logins, boolean online, long now) throws IOException {
        Map<String, JsonNode> copy = backup(bridge);
        boolean wiped = logins.entrySet().stream().anyMatch(e -> !hasCookies(e.getValue()) && copy.containsKey(e.getKey()));
        if (!wiped) {
            if (logins.values().stream().anyMatch(MetaSessions::hasCookies)) attempts.remove(bridge);
            return;
        }
        int tried = attempts.getOrDefault(bridge, 0);
        Long last = lastAttempt.get(bridge);
        if (tried >= MAX_ATTEMPTS || (last != null && now - last < RETRY_AFTER_MS)) return;
        if (!online || redirecting.test(bridge)) return; // it would only be erased again
        attempts.put(bridge, tried + 1);
        lastAttempt.put(bridge, now);
        System.err.println("Watchdog: restoring the " + bridge + " session its bridge erased (attempt " + (tried + 1) + ")");
        restoreAndRestart.accept(bridge);
    }

    /** Puts the copy back into logins that lost their cookies. With the bridge stopped. */
    void restore(String bridge) {
        try {
            Map<String, JsonNode> copy = backup(bridge);
            Map<String, ObjectNode> logins = logins(bridge);
            try (Connection c = open(bridge);
                 PreparedStatement update = c.prepareStatement("UPDATE user_login SET metadata = ? WHERE id = ?")) {
                for (Map.Entry<String, ObjectNode> e : logins.entrySet()) {
                    JsonNode cookies = copy.get(e.getKey());
                    if (hasCookies(e.getValue()) || cookies == null) continue;
                    e.getValue().set("cookies", cookies);
                    update.setString(1, JSON.writeValueAsString(e.getValue()));
                    update.setString(2, e.getKey());
                    update.executeUpdate();
                }
            }
        } catch (IOException | SQLException e) {
            System.err.println("Could not restore the " + bridge + " session: " + e.getClass().getSimpleName());
        }
    }

    private Map<String, ObjectNode> logins(String bridge) throws IOException, SQLException {
        Map<String, ObjectNode> out = new LinkedHashMap<>();
        try (Connection c = open(bridge);
             PreparedStatement q = c.prepareStatement("SELECT id, metadata FROM user_login");
             ResultSet r = q.executeQuery()) {
            while (r.next()) {
                if (JSON.readTree(r.getString(2)) instanceof ObjectNode metadata) out.put(r.getString(1), metadata);
            }
        }
        return out;
    }

    private Connection open(String bridge) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + database(bridge));
    }

    private Path database(String bridge) { return dir.resolve(bridge).resolve(bridge + ".db"); }

    private Path backupFile(String bridge) { return dir.resolve(bridge).resolve("session-backup.json"); }

    private static boolean hasCookies(ObjectNode metadata) {
        JsonNode c = metadata.get("cookies");
        return c != null && c.isObject() && !c.isEmpty();
    }

    private Map<String, JsonNode> backup(String bridge) throws IOException {
        Path f = backupFile(bridge);
        Map<String, JsonNode> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(f)) return out;
        JSON.readTree(f.toFile()).fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** As private as the database it comes from; written whole, so a crash can't leave half a session. */
    private void write(String bridge, Map<String, JsonNode> copy) throws IOException {
        SafeFile.replace(backupFile(bridge), JSON.writeValueAsBytes(copy), true);
    }
}
