package app.unichat.update;

import app.unichat.settings.Settings;
import app.unichat.support.AppVersion;
import app.unichat.support.Seen;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
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
import java.util.function.LongSupplier;

/**
 * Asks GitHub for Unichat's latest release (Ajustes → Privacidad, {@code app.checkUpdates}): a little after start, then
 * at most once a day, on its own daemon thread. Only GitHub's public API is contacted, with nothing about the person.
 * The answer is kept in settings, so a restart shows a known new version without asking again. Any failure (no
 * network, 404 while the repository is private, rate limit) is one line in the log and nothing on screen. While nobody
 * can see Unichat ({@link Seen}) the ticks don't ask; when it's seen again, one check runs if one is due.
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
    /** Set under the lock in start(); settingChanged() reads it on the worker. */
    private volatile boolean started;

    private final LongSupplier clock;
    private final Fetcher fetcher;

    /** What answers GitHub's call: the body, or an exception. */
    @FunctionalInterface
    interface Fetcher { String fetch() throws Exception; } // NOPMD: SignatureDeclareThrowsException, a callback: GitHub's call or a test's answer, failing as they do

    private UpdateChecker() { this(System::currentTimeMillis, UpdateChecker::fetch); }

    @SuppressFBWarnings(value = "SING_SINGLETON_HAS_NONPRIVATE_CONSTRUCTOR", justification = "package-private for the tests, which give a clock and a fetcher")
    UpdateChecker(LongSupplier clock, Fetcher fetcher) {
        this.clock = clock;
        this.fetcher = fetcher;
    }

    public static UpdateChecker get() { return INSTANCE; }

    /** Starts the daily check (the real app only: snapshots and tests never call it). Calling it again does nothing. */
    public synchronized void start() {
        if (started) return;
        started = true;
        listenToSeen();
        var _ = worker.scheduleWithFixedDelay(() -> { var _ = tick(); }, FIRST_LOOK.toSeconds(), LOOK_EVERY.toSeconds(), TimeUnit.SECONDS);
    }

    /** Seen again: looks at once whether a check is due (the ticks missed meanwhile count as one). */
    Consumer<Boolean> listenToSeen() {
        // Called under Seen's lock: only hands over to the worker.
        Consumer<Boolean> l = seen -> { if (seen) worker.execute(this::checkIfDue); };
        Seen.get().addListener(l);
        return l;
    }

    /** One tick of the daily look (the scheduled one too), on the worker; the future completes when it's done. */
    CompletableFuture<Void> tick() { return CompletableFuture.runAsync(this::checkIfDue, worker); }

    /** Completes when everything queued on the worker so far is done (tests wait on it, never on time). */
    CompletableFuture<Void> idle() { return CompletableFuture.runAsync(() -> { }, worker); }

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
        if (Seen.get().now() && Settings.get().checkUpdates() && UpdatePolicy.due(lastCheck(), clock.getAsLong())) check();
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
        s.set(LAST_CHECK, Long.toString(clock.getAsLong()));
        Result r;
        try {
            Release latest = UpdatePolicy.parse(fetcher.fetch());
            if (latest == null) throw new IllegalStateException("no release in the answer");
            s.set(LATEST, latest.version());
            s.set(LATEST_URL, latest.url());
            r = new Result(AppVersion.isNewer(latest.version(), AppVersion.current()) ? Outcome.NEWER : Outcome.UP_TO_DATE, latest);
        } catch (Exception e) { // NOPMD: ExceptionAsFlowControl, one failure path for no release and a failed call
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

    private static String fetch() throws IOException, InterruptedException {
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
