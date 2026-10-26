package app.unichat.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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

    /** Appservice port of each bridge (same as server/setup.sh). */
    static final Map<String, Integer> BRIDGE_PORTS = Map.of(
            "whatsapp", 29318, "telegram", 29317, "signal", 29328, "instagram", 29319, "facebook", 29320,
            "discord", 29334, "slack", 29335, "gmessages", 29336, "twitter", 29327, "linkedin", 29341);

    private static LocalServer instance;

    private final Platform platform;
    private final Path dir;
    private final Manifest manifest;
    private final BinaryStore store;
    private final Supervisor supervisor;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private boolean running;

    private LocalServer(Platform platform) {
        this.platform = platform;
        this.dir = platform.dataDir();
        this.manifest = Manifest.bundled();
        this.store = new BinaryStore(dir.resolve("bin"), platform);
        this.supervisor = new Supervisor(dir.resolve("run"));
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

    /** Login of the account on this server: {user, password}. Available after {@link #ensureRunning}. */
    public synchronized String[] credentials() throws IOException {
        Properties s = secrets();
        return new String[]{s.getProperty("user"), s.getProperty("password")};
    }

    /**
     * Installs what's missing, starts the server and the bridges already in use, and creates the account the first
     * time. Blocking (call off the FX thread); {@code progress} gets short Spanish status lines.
     */
    public synchronized void ensureRunning(Consumer<String> progress) throws IOException, InterruptedException {
        Properties secrets = secrets();
        if (!running) supervisor.reapOrphans();
        Files.createDirectories(dir.resolve("appservices"));
        progress.accept("Preparando el servidor…");
        store.installBundled("tuwunel", bundledHomeserver(), manifest.tuwunelVersion());
        writeDoublePuppet(secrets);
        for (String b : activeBridges()) prepareBridge(b, secrets, progress);
        writeHomeserverConfig(secrets);

        if (!supervisor.isRunning("tuwunel")) startHomeserver(progress);
        ensureAccount(secrets);
        for (String b : activeBridges()) {
            if (!supervisor.isRunning(b)) startBridge(b);
        }
        running = true;
    }

    /**
     * Makes {@code bridge} available before signing in to that network: downloads it the first time, registers
     * it with the server (a restart of a few seconds) and starts it. Blocking.
     */
    public synchronized void ensureBridge(String bridge, Consumer<String> progress) throws IOException, InterruptedException {
        if (!BRIDGE_PORTS.containsKey(bridge)) throw new IOException("Red no disponible en el servidor local: " + bridge);
        if (!running) ensureRunning(progress);
        Set<String> active = activeBridges();
        if (!active.contains(bridge)) {
            prepareBridge(bridge, secrets(), progress);
            active.add(bridge);
            Files.writeString(dir.resolve("bridges.active"), String.join(" ", active));
            // tuwunel reads appservice registrations at startup.
            progress.accept("Reiniciando el servidor…");
            supervisor.stop("tuwunel");
            startHomeserver(progress);
        }
        if (!supervisor.isRunning(bridge)) startBridge(bridge);
        progress.accept("Esperando al puente…");
        awaitPort(BRIDGE_PORTS.get(bridge), 60);
    }

    public synchronized void stop() {
        supervisor.stopAll();
        running = false;
    }

    // ---------------------------------------------------------------- pieces

    private void prepareBridge(String b, Properties secrets, Consumer<String> progress) throws IOException, InterruptedException {
        Manifest.Component c = manifest.bridge(b);
        if (c == null) throw new IOException("Sin versión fijada para " + b);
        if (!store.isInstalled(c)) {
            progress.accept("Descargando el puente de " + b + "…");
            store.install(c);
        }
        Path bd = dir.resolve(b);
        Files.createDirectories(bd);
        Path config = bd.resolve("config.yaml");
        if (!Files.exists(config)) run(bd, store.path(b).toString(), "-e", "-c", "config.yaml");
        String[] tg = telegramKeys(b);
        BridgeConfig.rewrite(config, new BridgeConfig.Settings(b, BRIDGE_PORTS.get(b), DOMAIN, "@" + USER + ":" + DOMAIN,
                URL, bd, secrets.getProperty("double_puppet_token"), tg[0], tg[1]));
        Path reg = bd.resolve("registration.yaml");
        if (!Files.exists(reg)) run(bd, store.path(b).toString(), "-g", "-c", "config.yaml", "-r", "registration.yaml");
        Files.copy(reg, dir.resolve("appservices").resolve(b + ".yaml"), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Telegram only runs with the user's own api_id/api_hash (my.telegram.org). */
    private String[] telegramKeys(String b) throws IOException {
        if (!b.equals("telegram")) return new String[]{null, null};
        app.unichat.settings.Settings s = app.unichat.settings.Settings.get();
        String id = s.getString("server.telegram.apiId", null), hash = s.getString("server.telegram.apiHash", null);
        if (id == null || id.isBlank() || hash == null || hash.isBlank()) {
            throw new IOException("Telegram necesita tu api_id y api_hash de my.telegram.org");
        }
        return new String[]{id.strip(), hash.strip()};
    }

    private void startHomeserver(Consumer<String> progress) throws IOException, InterruptedException {
        progress.accept("Iniciando el servidor…");
        Path hs = dir.resolve("tuwunel");
        supervisor.start("tuwunel", hs, List.of(store.path("tuwunel").toString(), "-c", hs.resolve("tuwunel.toml").toString()),
                hs.resolve("tuwunel.log"));
        long end = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < end) {
            if (answers()) return;
            Thread.sleep(500);
        }
        throw new IOException("El servidor no respondió; revisa " + hs.resolve("tuwunel.log"));
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
        throw new IOException("El puente no arrancó (puerto " + port + ")");
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
                """.formatted(secrets.getProperty("double_puppet_token"), random(48), DOMAIN));
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
        if (!done.has("user_id")) throw new IOException("No se pudo crear la cuenta: " + done);
        markAccountCreated(secrets);
    }

    private void markAccountCreated(Properties secrets) throws IOException {
        secrets.setProperty("account_created", "yes");
        saveSecrets(secrets);
    }

    private JsonNode postJson(String path, JsonNode body) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(URL + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        return new ObjectMapper().readTree(r.body());
    }

    private Set<String> activeBridges() throws IOException {
        Path f = dir.resolve("bridges.active");
        Set<String> out = new LinkedHashSet<>();
        if (Files.exists(f)) {
            for (String b : Files.readString(f).strip().split("\\s+")) if (!b.isBlank()) out.add(b);
        }
        return out;
    }

    /** Folder of the app's own files: the homeserver shipped with it. -Dunichat.tuwunelBin wins (development). */
    private Path bundledHomeserver() throws IOException {
        String override = System.getProperty("unichat.tuwunelBin");
        if (override != null) return Path.of(override);
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher != null) {
            Path l = Path.of(launcher);
            // macOS: Unichat.app/Contents/MacOS/Unichat → Contents/app; Windows: Unichat\Unichat.exe → Unichat\app
            Path app = platform == Platform.MAC_ARM64 ? l.getParent().resolveSibling("app") : l.getParent().resolve("app");
            Path bin = app.resolve("server").resolve("tuwunel" + platform.exeSuffix());
            if (Files.exists(bin)) return bin;
        }
        throw new IOException("Esta copia de Unichat no trae el servidor (tuwunel)");
    }

    // ---------------------------------------------------------------- secrets

    private Properties secrets() throws IOException {
        Path f = dir.resolve("secrets.properties");
        Properties p = new Properties();
        if (Files.exists(f)) {
            try (InputStream in = Files.newInputStream(f)) {
                p.load(in);
            }
        }
        boolean changed = false;
        for (String[] kv : new String[][]{{"user", USER}, {"password", null}, {"registration_token", null},
                {"double_puppet_token", null}}) {
            if (p.getProperty(kv[0]) == null) {
                p.setProperty(kv[0], kv[1] != null ? kv[1] : random(kv[0].equals("password") ? 24 : 48));
                changed = true;
            }
        }
        if (changed) saveSecrets(p);
        return p;
    }

    private void saveSecrets(Properties p) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve("secrets.properties");
        try (OutputStream out = Files.newOutputStream(f)) {
            p.store(out, "Unichat local server - private");
        }
        f.toFile().setReadable(false, false);
        f.toFile().setReadable(true, true);
    }

    private static String random(int n) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    private static void run(Path workDir, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(new ArrayList<>(List.of(cmd))).directory(workDir.toFile())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("Tiempo agotado: " + String.join(" ", cmd));
        }
    }
}
