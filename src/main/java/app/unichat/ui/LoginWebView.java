package app.unichat.ui;

import app.unichat.login.BridgeCookies;
import app.unichat.login.BridgeLogin;
import app.unichat.login.CookieSpec;
import app.unichat.login.SessionCookies;
import app.unichat.model.Network;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Modal window showing a network's real login page. The user signs in as on the website
 * (password, 2FA, checkpoints, captchas); once the session exists (every required cookie, or a token the page
 * holds) the window closes itself and hands it back. Values are never logged.
 */
public final class LoginWebView {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static final long HEADER_WAIT_MS = 8_000;

    /**
     * JavaFX's HTTP client keeps the handler it first saw for good (HTTP2Loader.HTTP_CLIENT), so a second window's cookies
     * went to the first window's store and it waited forever. Every window goes through this one, which forwards to its own.
     */
    private static final class CurrentCookies extends CookieHandler {
        volatile CookieManager store;

        @Override
        public Map<String, java.util.List<String>> get(java.net.URI uri, Map<String, java.util.List<String>> headers) throws java.io.IOException {
            CookieManager s = store;
            return s == null ? Map.of() : s.get(uri, headers);
        }

        @Override
        public void put(java.net.URI uri, Map<String, java.util.List<String>> headers) throws java.io.IOException {
            CookieManager s = store;
            if (s != null) s.put(uri, headers);
        }
    }

    private static final CurrentCookies COOKIES = new CurrentCookies();

    private static void stopCookies(CookieManager manager, CookieHandler previous) {
        if (COOKIES.store != manager) return; // somebody else's window by now
        COOKIES.store = null;
        CookieHandler.setDefault(previous);
    }

    private LoginWebView() {}

    /** The single open login window; only one may swap the JVM-wide cookie handler at a time. */
    private static Stage openStage;

    /** True while a login window is open. */
    public static boolean isOpen() { return openStage != null; }

    public static void open(Window owner, Network network, CookieSpec spec,
                            Consumer<SessionCookies> onDone, Runnable onCancel) {
        openWindow(owner, network, LoginUrls.startUrl(network), LoginUrls.userAgent(), (engine, manager) -> {
            Map<String, String> byName = new HashMap<>();
            for (HttpCookie c : manager.getCookieStore().getCookies()) {
                if (c.hasExpired() || !spec.isForNetwork(c.getDomain())) continue;
                byName.put(c.getName(), c.getValue());
            }
            SessionCookies s = spec.select(byName);
            return s.complete() ? () -> onDone.accept(s) : null;
        }, onCancel);
    }

    /**
     * Same window, for bridges that want what the page itself sends (LinkedIn's request headers). The window can't read
     * outgoing headers, but scripts on the page can see the ones the page sets, so it notes those as they go by, and
     * builds the Cookie header from the cookie store, which HttpOnly cookies are in as well.
     */
    public static void openCapturing(Window owner, Network network, BridgeLogin.CookieParams params,
                                     Consumer<Map<String, String>> onDone, Runnable onCancel) {
        String domain = BridgeCookies.cookieDomain(params);
        String hook = headerHook(BridgeCookies.headersToWatch(params));
        Map<String, String> fallbacks = BridgeCookies.headerFallbacks(network, params);
        long[] signedInAt = {0};
        // The bridge will use this session as this browser: signing in as another one can get it closed.
        String agent = params.userAgent() == null || params.userAgent().isBlank() ? LoginUrls.userAgent() : params.userAgent();
        openWindow(owner, network, params.url(), agent, (engine, manager) -> {
            Map<String, String> headers;
            try {
                engine.executeScript(hook);
                headers = seenHeaders(engine.executeScript("JSON.stringify(window.__unichatHeaders || {})"));
            } catch (RuntimeException e) {
                return null; // page not ready yet
            }
            Map<String, String> cookies = new LinkedHashMap<>();
            for (HttpCookie c : manager.getCookieStore().getCookies()) {
                if (!c.hasExpired() && BridgeCookies.belongs(c.getDomain(), domain)) cookies.put(c.getName(), c.getValue());
            }
            if (!BridgeCookies.signedIn(network, cookies)) return null;
            Map<String, String> values = BridgeCookies.captured(params, engine.getLocation(), cookies, headers);
            if (values != null) return () -> onDone.accept(values);
            // Signed in, but the page isn't showing its headers: give it a moment, then manage without them if that's possible.
            Map<String, String> fallback = BridgeCookies.captured(params, engine.getLocation(), cookies, headers, fallbacks);
            if (fallback == null) {
                signedInAt[0] = 0;
                return null;
            }
            if (signedInAt[0] == 0) signedInAt[0] = System.currentTimeMillis();
            return System.currentTimeMillis() - signedInAt[0] < HEADER_WAIT_MS ? null : () -> onDone.accept(fallback);
        }, onCancel);
    }

    /** Wraps XMLHttpRequest and fetch once per page, keeping only the wanted headers. It never changes what's sent. */
    static String headerHook(java.util.Set<String> wanted) {
        String names;
        try {
            names = JSON.writeValueAsString(wanted);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return """
                (function () {
                  if (window.__unichatHooked) return;
                  window.__unichatHooked = true;
                  window.__unichatHeaders = {};
                  var wanted = %s;
                  function note(name, value) {
                    var n = String(name).toLowerCase();
                    if (value && wanted.indexOf(n) >= 0) window.__unichatHeaders[n] = String(value);
                  }
                  var set = XMLHttpRequest.prototype.setRequestHeader;
                  XMLHttpRequest.prototype.setRequestHeader = function (name, value) {
                    note(name, value);
                    return set.apply(this, arguments);
                  };
                  if (window.fetch) {
                    var original = window.fetch;
                    window.fetch = function (input, init) {
                      try {
                        var h = (init && init.headers) || (input && input.headers);
                        if (h && typeof h.forEach === 'function' && !Array.isArray(h)) h.forEach(function (v, n) { note(n, v); });
                        else if (h) for (var k in h) note(k, h[k]);
                      } catch (e) {}
                      return original.apply(this, arguments);
                    };
                  }
                })();
                """.formatted(names);
    }

    private static Map<String, String> seenHeaders(Object json) {
        Map<String, String> out = new HashMap<>();
        try {
            JSON.readTree(String.valueOf(json)).fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return out; // never the contents: these are credentials
        }
        return out;
    }

    /**
     * Same window, finishing when {@code extractJs} (run in the page every second and after each load) returns a
     * string {@code valid} accepts, e.g. Discord's account token, which lives in the page and not in a cookie.
     * The value is never logged.
     */
    public static void openForToken(Window owner, Network network, String url, String extractJs,
                                    Predicate<String> valid, Consumer<String> onToken, Runnable onCancel) {
        openWindow(owner, network, url, LoginUrls.userAgent(), (engine, manager) -> {
            Object r;
            try {
                r = engine.executeScript(extractJs);
            } catch (RuntimeException e) {
                return null; // page not ready yet
            }
            return r instanceof String t && valid.test(t) ? () -> onToken.accept(t) : null;
        }, onCancel);
    }

    /**
     * The window: shows {@code url}; every 1.5 s and after each page load asks {@code finish} whether the login
     * is done; a non-null answer closes the window and then runs it.
     */
    /** Fresh local storage per window (another account can log in; nothing stays behind). */
    private static Path freshStorage(WebEngine engine) {
        Path storage = null;
        try {
            storage = Files.createTempDirectory("unichat-login");
            engine.setUserDataDirectory(storage.toFile());
        } catch (IOException | RuntimeException ignored) {
            // Default storage: login still works.
        }
        return storage;
    }

    private static HBox cancelBar(Network network, Button cancel) {
        Label hint = new Label("Inicia sesión como en la web de " + network.displayName()
                + ". Puede tardar unos segundos en cargar; la ventana se cerrará sola al terminar.");
        hint.getStyleClass().add("settings-help");
        hint.setWrapText(true);
        cancel.setAccessibleText("Cancelar el inicio de sesión en " + network.displayName());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, hint, spacer, cancel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 14, 10, 14));
        bar.getStyleClass().add("settings-row");
        return bar;
    }

    private static void openWindow(Window owner, Network network, String url, String userAgent,
                                   BiFunction<WebEngine, CookieManager, Runnable> finish, Runnable onCancel) {
        if (openStage != null) { // never swap the global handler twice
            openStage.toFront();
            openStage.requestFocus();
            return;
        }
        CookieHandler previous = CookieHandler.getDefault();
        CookieManager manager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

        WebView web = new WebView();
        web.setContextMenuEnabled(false);
        WebEngine engine = web.getEngine();
        engine.setUserAgent(userAgent);
        Path storageDir = freshStorage(engine);
        web.setAccessibleText("Página de inicio de sesión de " + network.displayName());

        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("Iniciar sesión en " + network.displayName());

        Button cancel = new Button("Cancelar");
        HBox bar = cancelBar(network, cancel);

        BorderPane root = new BorderPane(web);
        root.setTop(bar);
        root.getStyleClass().add("settings-root");
        Scene scene = new Scene(root, 520, 760);
        Theme.register(scene);
        stage.setScene(scene);

        boolean[] finished = {false};
        Timeline poll = new Timeline();

        Runnable check = () -> {
            if (finished[0]) return;
            Runnable done = finish.apply(engine, manager);
            if (done != null) {
                finished[0] = true;
                stage.close();
                done.run();
            }
        };

        poll.getKeyFrames().add(new KeyFrame(Duration.seconds(1.5), e -> check.run()));
        poll.setCycleCount(Animation.INDEFINITE);
        engine.getLoadWorker().stateProperty().addListener((o, a, s) -> {
            if (s == Worker.State.SUCCEEDED) check.run();
        });

        cancel.setOnAction(e -> {
            if (!finished[0]) {
                finished[0] = true;
                stage.close();
                onCancel.run();
            }
        });
        stage.setOnHidden(e -> {
            poll.stop();
            engine.load("about:blank");
            stopCookies(manager, previous);
            openStage = null;
            deleteQuietly(storageDir);
            if (!finished[0]) {
                finished[0] = true; // closed with the window button
                Platform.runLater(onCancel);
            }
        });

        // Install the global handler only after all risky setup succeeded; undo on failure.
        // WebView reads the JVM-wide default handler: give it a fresh, empty store while open.
        COOKIES.store = manager;
        CookieHandler.setDefault(COOKIES);
        openStage = stage;
        try {
            stage.show();
            poll.play();
            engine.load(url);
        } catch (RuntimeException ex) {
            poll.stop();
            stage.close(); // no orphan modal window
            stopCookies(manager, previous);
            openStage = null;
            throw ex;
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (var walk = Files.walk(dir)) {
            for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
        } catch (IOException ignored) {
            // Temp folder: the OS cleans it eventually.
        }
    }
}
