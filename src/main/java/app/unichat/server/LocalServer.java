package app.unichat.server;

import app.unichat.support.BundledTools;
import app.unichat.settings.Settings;
import app.unichat.bridge.BridgePorts;
import app.unichat.bridge.BridgeStates;
import app.unichat.support.I18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The personal server on this computer, with no Docker: installs whatever is missing (homeserver shipped with
 * the app, bridges downloaded on demand), writes the configs, starts and supervises the processes and owns the
 * account Unichat signs in with. See docs/SERVIDOR-NATIVO.md.
 */
public final class LocalServer {
    public static final int PORT = 8008;
    public static final String URL = "http://127.0.0.1:" + PORT;
    static final String DOMAIN = "localhost";
    static final String USER = "yo";

    private static LocalServer instance;

    private final Platform platform;
    private final Path dir;
    private final Manifest manifest;
    private final BinaryStore store;
    private final ServerSecrets secretsFile;
    private final BridgeInstaller installer;
    private final Supervisor supervisor;
    private final MetaSessions metaSessions;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private boolean running;
    /** Live connection state of every bridge account, reported by the bridges themselves. */
    private final BridgeStates states = new BridgeStates();
    private final StatusEndpoint statusEndpoint = new StatusEndpoint(states, this::asToken);
    private final java.util.Map<String, String> asTokens = new java.util.concurrent.ConcurrentHashMap<>();
    private final Watchdog watchdog;

    private LocalServer(Platform platform) {
        this.platform = platform;
        this.dir = platform.dataDir();
        this.manifest = Manifest.bundled();
        this.store = new BinaryStore(dir.resolve("bin"), platform);
        this.secretsFile = new ServerSecrets(dir);
        this.installer = new BridgeInstaller(dir, store, manifest);
        this.supervisor = new Supervisor(dir.resolve("run"));
        java.util.Map<String, Integer> ports = new java.util.HashMap<>(BridgePorts.BRIDGE_PORTS);
        ports.put("tuwunel", PORT);
        this.metaSessions = new MetaSessions(dir, states, MetaSessions::redirecting, this::restoreSession);
        this.watchdog = new Watchdog(ports, this::watched, states, (name, why) -> supervisor.kill(name), Internet::reachable,
                metaSessions);
        // Whatever way Unichat ends (Salir, ⌘Q, logout of the Mac, kill), its server and bridges end with it.
        Runtime.getRuntime().addShutdownHook(new Thread(supervisor::stopAll, "local-server-shutdown"));
    }

    /** Null on systems the native stack doesn't support (Intel Mac, Linux). */
    public static synchronized LocalServer get() {
        if (instance == null) {
            Platform p = Platform.current();
            if (p != null) instance = new LocalServer(p);
        }
        return instance;
    }

    public Path dir() { return dir; }

    /** What the bridges report about their accounts (live; listeners run on a background thread). */
    public BridgeStates states() { return states; }

    private synchronized void restoreSession(String bridge) {
        try {
            if (!running || !usableBridges().contains(bridge)) return;
            supervisor.stop(bridge);
            metaSessions.restore(bridge);
            states.restarting(bridge, BridgeStates.now());
            startBridge(bridge);
        } catch (IOException e) {
            System.err.println("Could not restart " + bridge + " with its session: " + e.getClass().getSimpleName());
        }
    }

    /** The processes that should be up: the server and the bridges in use. */
    private Iterable<String> watched() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (supervisor.isRunning("tuwunel") || running) out.add("tuwunel");
        try {
            out.addAll(usableBridges());
        } catch (IOException e) {
            // no list yet
        }
        return out;
    }

    /** A bridge's as_token, from its registration (it authorizes the bridge's status reports). */
    private String asToken(String bridge) {
        if (!BridgePorts.BRIDGE_PORTS.containsKey(bridge)) return null;
        return asTokens.computeIfAbsent(bridge, b -> {
            try {
                return BridgeConfig.read(Files.readString(dir.resolve(b).resolve("registration.yaml"))).path("as_token").asText(null);
            } catch (IOException e) {
                return null;
            }
        });
    }

    /** The bridge's login API (bridgev2 bridges serve it on their appservice port); null for legacy bridges. */
    public String provisioningUrl(String bridge) {
        Integer port = BridgePorts.BRIDGE_PORTS.get(bridge);
        return port == null || BridgeInstaller.LEGACY_BRIDGES.contains(bridge) ? null : "http://127.0.0.1:" + port + "/_matrix/provision";
    }

    /** Whether this server can install {@code bridge} here (pinned in versions.env, with a known port). */
    public boolean provides(String bridge) {
        return BridgePorts.BRIDGE_PORTS.containsKey(bridge) && manifest.bridge(bridge) != null;
    }

    /** Login of the account on this server: {user, password}. Available after {@link #ensureRunning}. */
    public synchronized String[] credentials() throws IOException {
        Properties s = secretsFile.load();
        return new String[]{s.getProperty("user"), s.getProperty("password")};
    }

    /**
     * Installs what's missing, starts the server and the bridges already in use, and creates the account the first
     * time. Blocking (call off the FX thread); {@code progress} gets short Spanish status lines.
     */
    public synchronized void ensureRunning(Consumer<String> progress) throws IOException, InterruptedException {
        Properties secrets = secretsFile.load();
        if (!running) supervisor.reapOrphans();
        Files.createDirectories(dir.resolve("appservices"));
        progress.accept(I18n.t("server.preparing"));
        store.installBundled("tuwunel", bundledHomeserver(), manifest.tuwunelVersion());
        installLibolm();
        writeDoublePuppet(secrets);
        if (activeBridges().contains("telegram") && !TelegramKeys.saved()) {
            System.err.println("Telegram not started: no valid API keys (Ajustes → Redes)");
        }
        for (String b : usableBridges()) prepareBridge(b, secrets, progress);
        writeHomeserverConfig(secrets);

        if (!supervisor.isRunning("tuwunel")) startHomeserver(progress);
        ensureAccount(secrets);
        for (String b : usableBridges()) {
            if (!supervisor.isRunning(b)) startBridge(b);
        }
        running = true;
        watchdog.start();
    }

    /**
     * Makes {@code bridge} available before signing in to that network: downloads it the first time, registers
     * it with the server (a restart of a few seconds) and starts it. Blocking.
     */
    public synchronized void ensureBridge(String bridge, Consumer<String> progress) throws IOException, InterruptedException {
        if (!BridgePorts.BRIDGE_PORTS.containsKey(bridge)) throw new IOException(I18n.t("server.networkUnavailable", bridge));
        if (!running) ensureRunning(progress);
        Set<String> active = activeBridges();
        if (!active.contains(bridge)) {
            prepareBridge(bridge, secretsFile.load(), progress);
            active.add(bridge);
            Files.writeString(dir.resolve("bridges.active"), String.join(" ", active));
            // tuwunel reads appservice registrations at startup.
            progress.accept(I18n.t("server.restarting"));
            supervisor.stop("tuwunel");
            startHomeserver(progress);
        }
        if (!supervisor.isRunning(bridge)) {
            // Skipped at startup if its keys were missing then: its config needs them now.
            prepareBridge(bridge, secretsFile.load(), progress);
            startBridge(bridge);
        }
        progress.accept(I18n.t("server.waitingBridge"));
        awaitPort(BridgePorts.BRIDGE_PORTS.get(bridge), 60);
    }

    public synchronized void stop() {
        watchdog.stop();
        supervisor.stopAll();
        statusEndpoint.stop();
        running = false;
    }

    // ---------------------------------------------------------------- pieces

    private void prepareBridge(String b, Properties secrets, Consumer<String> progress) throws IOException, InterruptedException {
        BridgeInstaller.TelegramApi telegram = b.equals("telegram") && TelegramKeys.saved()
                ? new BridgeInstaller.TelegramApi(TelegramKeys.apiId().strip(), TelegramKeys.apiHash().strip()) : null;
        installer.prepare(b, secrets.getProperty("double_puppet_token"), statusEndpoint.url(b), telegram, progress);
    }

    private void startHomeserver(Consumer<String> progress) throws IOException, InterruptedException {
        progress.accept(I18n.t("server.starting"));
        Path hs = dir.resolve("tuwunel");
        List<String> command = new ArrayList<>(List.of(store.path("tuwunel").toString(), "-c", hs.resolve("tuwunel.toml").toString()));
        // Old Discord attachments go before the bridge starts (it must not be using its database meanwhile).
        if (!supervisor.isRunning("discord")) {
            int days = Settings.get().discordMediaDays();
            List<String> old = MediaPrune.takeOldDiscordAttachments(dir.resolve("discord").resolve("discord.db"),
                    days == 0 ? null : Duration.ofDays(days), java.time.Instant.now());
            if (!old.isEmpty()) {
                System.err.println("Discord: deleting " + old.size() + " old attachments");
                command.addAll(List.of("--execute", MediaPrune.command(old)));
            }
        }
        supervisor.start("tuwunel", hs, command, hs.resolve("tuwunel.log"));
        long end = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < end) {
            if (answers()) return;
            Thread.sleep(500);
        }
        throw new IOException(I18n.t("server.noAnswer", hs.resolve("tuwunel.log")));
    }

    private boolean answers() {
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(URL + "/_matrix/client/versions"))
                    .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.discarding());
            return r.statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void startBridge(String b) throws IOException {
        Path bd = dir.resolve(b);
        supervisor.start(b, bd, List.of(store.path(b).toString(), "-c", "config.yaml"), bd.resolve("bridge.log"));
    }

    private static void awaitPort(int port, int seconds) throws IOException, InterruptedException {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (IOException e) {
                Thread.sleep(500);
            }
        }
        throw new IOException(I18n.t("server.bridgeNoStart", port));
    }

    private void writeHomeserverConfig(Properties secrets) throws IOException {
        Path hs = dir.resolve("tuwunel");
        Files.createDirectories(hs.resolve("db"));
        Files.writeString(hs.resolve("tuwunel.toml"), HomeserverConfig.toml(dir, PORT, DOMAIN, secrets.getProperty("registration_token")));
    }

    /** Lets bridges send what you write on your phone as you (written once, tokens must stay stable). */
    private void writeDoublePuppet(Properties secrets) throws IOException {
        Path f = dir.resolve("appservices").resolve("doublepuppet.yaml");
        if (Files.exists(f)) return;
        Files.writeString(f, """
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
                """.formatted(secrets.getProperty("double_puppet_token"), ServerSecrets.random(48), DOMAIN));
    }

    /** Registers the account the first time (registration-token flow, two requests). */
    private void ensureAccount(Properties secrets) throws IOException, InterruptedException {
        if ("yes".equals(secrets.getProperty("account_created"))) return;
        ObjectMapper json = new ObjectMapper();
        ObjectNode body = json.createObjectNode()
                .put("username", secrets.getProperty("user"))
                .put("password", secrets.getProperty("password"))
                .put("inhibit_login", true);
        JsonNode first = postJson("/_matrix/client/v3/register", body);
        if ("M_USER_IN_USE".equals(first.path("errcode").asText())) {
            markAccountCreated(secrets);
            return;
        }
        body.putObject("auth").put("type", "m.login.registration_token")
                .put("token", secrets.getProperty("registration_token"))
                .put("session", first.path("session").asText(""));
        JsonNode done = postJson("/_matrix/client/v3/register", body);
        if (!done.has("user_id")) throw new IOException(I18n.t("server.accountFailed", done));
        markAccountCreated(secrets);
    }

    private void markAccountCreated(Properties secrets) throws IOException {
        secrets.setProperty("account_created", "yes");
        secretsFile.save(secrets);
    }

    private JsonNode postJson(String path, JsonNode body) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(URL + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        return new ObjectMapper().readTree(r.body());
    }

    /**
     * The bridges in use that can run: Telegram without valid keys is left out, so one network's missing keys never
     * keep the server and the other networks from starting.
     */
    private Set<String> usableBridges() throws IOException {
        Set<String> out = new LinkedHashSet<>(activeBridges());
        if (!TelegramKeys.saved()) out.remove("telegram");
        return out;
    }

    /** Applies new settings to a bridge that's in use (its config is rewritten, then it restarts). Blocking. */
    public synchronized void restartBridge(String bridge, Consumer<String> progress) throws IOException, InterruptedException {
        if (!running || !usableBridges().contains(bridge)) return;
        prepareBridge(bridge, secretsFile.load(), progress);
        supervisor.stop(bridge);
        states.restarting(bridge, BridgeStates.now());
        startBridge(bridge);
    }

    private Set<String> activeBridges() throws IOException {
        Path f = dir.resolve("bridges.active");
        Set<String> out = new LinkedHashSet<>();
        if (Files.exists(f)) {
            for (String b : Files.readString(f).strip().split("\\s+")) if (!b.isBlank()) out.add(b);
        }
        return out;
    }

    /** The homeserver shipped with the app. -Dunichat.tuwunelBin wins (development). */
    private Path bundledHomeserver() throws IOException {
        return bundled("tuwunel" + platform.exeSuffix(), "unichat.tuwunelBin");
    }

    /** A file shipped inside the app ({@link BundledTools#appFile}); {@code -D<override>} wins during development. */
    private Path bundled(String file, String override) throws IOException {
        String o = System.getProperty(override);
        if (o != null) return Path.of(o);
        Path f = BundledTools.appFile(file);
        if (f != null && Files.exists(f)) return f; // exists, not executable: libolm isn't
        throw new IOException(I18n.t("server.missingFile", file));
    }

    /**
     * macOS: the official bridge binaries link @rpath/libolm.3.dylib and look next to themselves (Homebrew no
     * longer has it). Unichat ships one (native/build-libolm.sh); bridge encryption is off, it's only loaded.
     */
    private void installLibolm() throws IOException {
        if (platform != Platform.MAC_ARM64) return;
        store.installBundled("libolm.3.dylib", bundled("libolm.3.dylib", "unichat.libolm"), LIBOLM_VERSION);
    }

    static final String LIBOLM_VERSION = "3.2.16";
}
