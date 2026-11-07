package app.unichat.login;

import app.unichat.model.Network;
import app.unichat.support.Folders;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Signing in through the person's real Chrome, for what doesn't work in an embedded window: Google refuses those.
 *
 * <p>Chrome runs on an empty profile of its own. Only the tab that opens the site presents itself as the browser the bridge will
 * use, since LinkedIn cancels a session used under a different identity (mautrix/linkedin#61). Google's window is a tab of its own:
 * nothing is attached to it, so it sees Chrome as it is, because Google refuses a browser that contradicts itself. The session is
 * read once the page reaches the end of the login, and the profile, signed-in Google included, is then deleted.
 */
public final class ChromeLogin {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long POLL_MS = 1_500;
    private static final long MAX_WAIT_MS = 15 * 60_000;
    private static final int NO_TABS_LIMIT = 3;
    private static final String BLANK = "about:blank";

    private final Network network;
    private final BridgeLogin.CookieParams params;
    private final Path chrome;
    private final Path profile;
    private final boolean headless;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile Process process;
    private volatile int debugPort;
    private volatile DevTools siteTab;
    private volatile boolean cancelled;

    public ChromeLogin(Network network, BridgeLogin.CookieParams params, Path chrome, Path profile) {
        this(network, params, chrome, profile, false);
    }

    ChromeLogin(Network network, BridgeLogin.CookieParams params, Path chrome, Path profile, boolean headless) {
        this.network = network;
        this.params = params;
        this.chrome = chrome;
        this.profile = profile;
        this.headless = headless;
    }

    /** Whether this network needs it: only LinkedIn, whose bridge has the identity problem and whose users sign in with Google. */
    public static boolean usefulFor(Network n) { return n == Network.LINKEDIN; }

    /** Chrome, or a browser built on it, if there's one; null otherwise. */
    public static Path findChrome() {
        for (Path p : candidates()) {
            if (Files.isExecutable(p)) return p;
        }
        return null;
    }

    private static List<Path> candidates() {
        List<Path> out = new ArrayList<>();
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            for (String env : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
                String base = System.getenv(env);
                if (base == null) continue;
                out.add(Path.of(base, "Google", "Chrome", "Application", "chrome.exe"));
                out.add(Path.of(base, "Microsoft", "Edge", "Application", "msedge.exe"));
                out.add(Path.of(base, "BraveSoftware", "Brave-Browser", "Application", "brave.exe"));
            }
        } else {
            out.add(Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
            out.add(Path.of("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"));
            out.add(Path.of("/Applications/Brave Browser.app/Contents/MacOS/Brave Browser"));
        }
        return out;
    }

    /**
     * Never {@code --enable-automation} nor port 0, which makes {@code navigator.webdriver} true and has Google reject the browser
     * (measured: a chosen port leaves it false); nor {@code --use-mock-keychain}: the person's passkeys live behind the real one.
     */
    static List<String> command(Path chrome, Path profile, int port, boolean headless) {
        List<String> c = new ArrayList<>(List.of(chrome.toString(), "--user-data-dir=" + profile, "--remote-debugging-port=" + port,
                "--no-first-run", "--no-default-browser-check"));
        if (headless) c.addAll(List.of("--headless=new", "--disable-popup-blocking"));
        c.add(BLANK);
        return c;
    }

    /** The identity for one tab, client hints included: a {@code --user-agent} flag would apply to Google too and leave the hints saying otherwise. */
    static String identity(String userAgent) {
        boolean windows = userAgent.contains("Windows");
        boolean mac = userAgent.contains("Macintosh");
        ObjectNode params = JSON.createObjectNode().put("userAgent", userAgent)
                .put("platform", windows ? "Win32" : mac ? "MacIntel" : "Linux x86_64");
        Matcher chromeVersion = Pattern.compile("Chrome/(\\d+)").matcher(userAgent);
        if (chromeVersion.find()) {
            String major = chromeVersion.group(1);
            ObjectNode hints = params.putObject("userAgentMetadata");
            ArrayNode brands = hints.putArray("brands");
            brands.addObject().put("brand", "Google Chrome").put("version", major);
            brands.addObject().put("brand", "Not?A_Brand").put("version", "8");
            brands.addObject().put("brand", "Chromium").put("version", major);
            hints.put("platform", windows ? "Windows" : mac ? "macOS" : "Linux").put("platformVersion", "")
                    .put("architecture", "x86").put("model", "").put("mobile", false).put("bitness", "64");
        }
        return JSON.createObjectNode().put("id", 1).put("method", "Emulation.setUserAgentOverride").set("params", params).toString();
    }

    /**
     * macOS charges a process's permissions to whoever started it, and Unichat doesn't declare Bluetooth: a child Chrome can't reach
     * the phone for a passkey. {@code open} makes it an app of its own, and {@code -W} keeps this process alive as long as Chrome.
     */
    static List<String> launched(List<String> command) {
        Path bundle = Path.of(command.get(0)).getParent();
        for (int up = 0; up < 2 && bundle != null; up++) bundle = bundle.getParent();
        if (bundle == null || !bundle.toString().endsWith(".app")) return command;
        List<String> out = new ArrayList<>(List.of("/usr/bin/open", "-n", "-W", "-a", bundle.toString(), "--args"));
        out.addAll(command.subList(1, command.size()));
        return out;
    }

    /** Blocks until signed in: the step's values, or null if the person closed Chrome or {@link #cancel cancelled}. */
    public Map<String, String> run() throws IOException, InterruptedException {
        Folders.deleteQuietly(profile);
        Files.createDirectories(profile);
        try {
            int port = freePort();
            process = new ProcessBuilder(launched(command(chrome, profile, port, headless)))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            awaitDebugPort(port);
            debugPort = port;
            openSite(port);
            Pattern end = Pattern.compile(params.waitForUrlPattern() == null ? ".*" : params.waitForUrlPattern());
            String domain = BridgeCookies.cookieDomain(params);
            long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
            int noTabs = 0;
            while (!cancelled && process.isAlive() && System.currentTimeMillis() < deadline) {
                List<String> tabs = tabs(port);
                // On a Mac, closing the last window leaves the program running.
                noTabs = tabs.isEmpty() ? noTabs + 1 : 0;
                if (noTabs >= NO_TABS_LIMIT) return null;
                for (String url : tabs) {
                    if (!end.matcher(url).find()) continue;
                    Map<String, String> values = values(url, readCookies(port, domain));
                    if (values != null) return values;
                }
                Thread.sleep(POLL_MS);
            }
            return null;
        } finally {
            DevTools tab = siteTab;
            if (tab != null) tab.close();
            stop();
        }
    }

    /** The connection stays open during the login: the tab's identity lasts as long as it does. */
    private void openSite(int port) throws IOException, InterruptedException {
        String socket = null;
        for (int i = 0; i < 100 && socket == null && !cancelled; i++) {
            socket = blankTabSocketIn(get(port, "/json/list"));
            if (socket == null) Thread.sleep(100);
        }
        if (socket == null) throw new IOException("Chrome no arrancó");
        DevTools tab = new DevTools(socket);
        siteTab = tab;
        String agent = params.userAgent();
        if (agent != null && !agent.isBlank()) tab.call(identity(agent));
        tab.call(JSON.createObjectNode().put("id", 2).put("method", "Page.navigate")
                .set("params", JSON.createObjectNode().put("url", params.url())).toString());
    }

    /** The debugging address of the tab Chrome started with, if it's there yet. */
    static String blankTabSocketIn(String json) throws IOException {
        for (JsonNode t : JSON.readTree(json)) {
            if ("page".equals(t.path("type").asText()) && BLANK.equals(t.path("url").asText())) return t.path("webSocketDebuggerUrl").asText();
        }
        return null;
    }

    public void cancel() { cancelled = true; }

    /** The step's values from these cookies, or null while the session isn't there yet. */
    Map<String, String> values(String url, Map<String, String> cookies) {
        if (!BridgeCookies.signedIn(network, cookies)) return null;
        return BridgeCookies.captured(params, url, cookies, Map.of(), BridgeCookies.headerFallbacks(network, params));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private void awaitDebugPort(int port) throws IOException, InterruptedException {
        for (int i = 0; i < 200 && !cancelled && process.isAlive(); i++) {
            try {
                get(port, "/json/version");
                return;
            } catch (java.net.ConnectException notYet) {
                Thread.sleep(100);
            }
        }
        if (cancelled) throw new InterruptedException();
        throw new IOException("Chrome no arrancó");
    }

    private String get(int port, String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private List<String> tabs(int port) throws IOException, InterruptedException {
        try {
            return tabsIn(get(port, "/json/list"));
        } catch (java.net.ConnectException closing) {
            return List.of();
        }
    }

    /** The addresses of the open pages. */
    static List<String> tabsIn(String json) throws IOException {
        List<String> out = new ArrayList<>();
        for (JsonNode t : JSON.readTree(json)) {
            if ("page".equals(t.path("type").asText())) out.add(t.path("url").asText());
        }
        return out;
    }

    private Map<String, String> readCookies(int port, String domain) throws IOException, InterruptedException {
        return cookiesIn(send(port, "{\"id\":1,\"method\":\"Storage.getCookies\"}"), domain);
    }

    /** By name, the cookies of {@code domain}: values exactly as sent, quotes included. */
    static Map<String, String> cookiesIn(String response, String domain) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode c : JSON.readTree(response).path("result").path("cookies")) {
            if (BridgeCookies.belongs(c.path("domain").asText(), domain)) out.put(c.path("name").asText(), c.path("value").asText());
        }
        return out;
    }

    /** One request to the browser over its debugging socket, connected only for this. */
    private String send(int port, String message) throws IOException, InterruptedException {
        try (DevTools browser = new DevTools(JSON.readTree(get(port, "/json/version")).path("webSocketDebuggerUrl").asText())) {
            return browser.call(message);
        }
    }

    /** A debugging connection, one request at a time: no events are asked for, so all that arrives is the answer. */
    private final class DevTools implements WebSocket.Listener, AutoCloseable {
        private final StringBuilder text = new StringBuilder();
        private final WebSocket socket;
        private volatile CompletableFuture<String> answer = new CompletableFuture<>();

        DevTools(String url) throws IOException, InterruptedException {
            try {
                socket = http.newWebSocketBuilder().buildAsync(URI.create(url), this).get(5, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                throw new IOException("No se pudo conectar con Chrome");
            }
        }

        String call(String message) throws IOException, InterruptedException {
            answer = new CompletableFuture<>();
            socket.sendText(message, true);
            try {
                return answer.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                throw new IOException("Chrome no respondió");
            }
        }

        @Override public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                answer.complete(text.toString());
                text.setLength(0);
            }
            w.request(1);
            return null;
        }

        @Override public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
            answer.completeExceptionally(new IOException("closed"));
            return null;
        }

        @Override public void onError(WebSocket w, Throwable e) {
            answer.completeExceptionally(e);
        }

        @Override public void close() {
            socket.abort();
        }
    }

    /** Chrome is asked first: on a Mac the process here is only {@code open}, and killing that leaves Chrome running. */
    private void stop() throws InterruptedException {
        Process p = process;
        if (p != null && p.isAlive()) {
            try {
                if (debugPort > 0) send(debugPort, "{\"id\":1,\"method\":\"Browser.close\"}");
            } catch (IOException alreadyClosing) {
                // Chrome may close before answering; what's left is forced below.
            }
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                String mine = "--user-data-dir=" + profile;
                ProcessHandle.allProcesses()
                        .filter(h -> h.info().arguments().map(a -> List.of(a).contains(mine)).orElse(false))
                        .forEach(ProcessHandle::destroyForcibly);
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
        }
        // The session lives there: gone even if a file resists at first (Chrome still closing it), and again at the next login.
        Folders.deleteQuietly(profile);
    }
}
