package app.unichat.update;

import app.unichat.settings.Settings;
import app.unichat.support.AppVersion;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Asks GitHub for Unichat's latest release (Ajustes → Privacidad, {@code app.checkUpdates}): a little after start, then
 * at most once a day, on its own daemon thread. Only GitHub's public API is contacted, with nothing about the person.
 * The answer is kept in settings, so a restart shows a known new version without asking again. Any failure (no
 * network, 404 while the repository is private, rate limit) is one line in the log and nothing on screen.
 */
public final class UpdateChecker {
    public enum Outcome { UP_TO_DATE, NEWER, FAILED }

    /** What a check found: {@code release} is the latest (null when it {@link Outcome#FAILED}). */
    public record Result(Outcome outcome, Release release) {}

    static final String LAST_CHECK = "app.update.lastCheck";
    static final String LATEST = "app.update.latest";
    static final String LATEST_URL = "app.update.url";
    static final String DISMISSED = "app.update.dismissed";
    private static final URI API = URI.create("https://api.github.com/repos/vaoan/unichat/releases/latest");
    private static final Duration FIRST_LOOK = Duration.ofSeconds(20);
    /** How often the worker looks whether a day has passed: a sleeping computer doesn't count awake time. */
    private static final Duration LOOK_EVERY = Duration.ofHours(1);
    private static final UpdateChecker INSTANCE = new UpdateChecker();
    /** One for every check: each client keeps its own threads and connections. */
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "update-check");
        t.setDaemon(true);
        return t;
    });
    private final List<Consumer<Release>> listeners = new CopyOnWriteArrayList<>();
    private boolean started;

    private UpdateChecker() {}

    public static UpdateChecker get() { return INSTANCE; }

    /** Starts the daily check (the real app only: snapshots and tests never call it). Calling it again does nothing. */
    public synchronized void start() {
        if (started) return;
        started = true;
        worker.scheduleWithFixedDelay(this::checkIfDue, FIRST_LOOK.toSeconds(), LOOK_EVERY.toSeconds(), TimeUnit.SECONDS);
    }

    /** Hears the release to tell the person about, or null when there's none any more; on the worker's thread. */
    public void addListener(Consumer<Release> l) { listeners.add(l); }

    public void removeListener(Consumer<Release> l) { listeners.remove(l); }

    /** The newer version last heard of, unless dismissed or checks are off; null otherwise. */
    public Release pending() {
        Settings s = Settings.get();
        if (!s.checkUpdates()) return null;
        String tag = s.getString(LATEST, null);
        Release known = tag == null ? null : new Release(tag, s.getString(LATEST_URL, UpdatePolicy.RELEASES));
        return UpdatePolicy.shouldShow(known, AppVersion.current(), s.getString(DISMISSED, null)) ? known : null;
    }

    /** "Not now" for {@code r}: it isn't offered again, a later version will be. */
    public void dismiss(Release r) {
        worker.execute(() -> {
            Settings.get().set(DISMISSED, r.version());
            tell();
        });
    }

    /** After the setting changed: hide what's shown when turned off, look now when turned on (if a day has passed). */
    public void settingChanged() {
        worker.execute(() -> {
            tell();
            if (started) checkIfDue();
        });
    }

    /** Ajustes → Acerca de: asks now, whatever the setting and the last time (the person pressed the button). */
    public CompletableFuture<Result> checkNow() {
        return CompletableFuture.supplyAsync(this::check, worker);
    }

    private void checkIfDue() {
        if (Settings.get().checkUpdates() && UpdatePolicy.due(lastCheck(), System.currentTimeMillis())) check();
    }

    private static long lastCheck() {
        try {
            return Long.parseLong(Settings.get().getString(LAST_CHECK, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Result check() {
        Settings s = Settings.get();
        s.set(LAST_CHECK, Long.toString(System.currentTimeMillis()));
        Result r;
        try {
            Release latest = UpdatePolicy.parse(fetch());
            if (latest == null) throw new IllegalStateException("no release in the answer");
            s.set(LATEST, latest.version());
            s.set(LATEST_URL, latest.url());
            r = new Result(AppVersion.isNewer(latest.version(), AppVersion.current()) ? Outcome.NEWER : Outcome.UP_TO_DATE, latest);
        } catch (Exception e) {
            System.err.println("Update check failed: " + e.getMessage());
            r = new Result(Outcome.FAILED, null);
        }
        tell();
        return r;
    }

    private void tell() {
        Release p = pending();
        for (Consumer<Release> l : listeners) l.accept(p);
    }

    private static String fetch() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(API).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Unichat/" + AppVersion.current())
                .GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
        return res.body();
    }
}
