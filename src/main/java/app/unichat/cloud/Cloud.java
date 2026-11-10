package app.unichat.cloud;

import app.unichat.settings.Settings;
import app.unichat.support.Errors;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The person's own storage account, kept up to date while Unichat runs: the settings and what the AI learned stay the
 * same on every computer connected to it (applied at once, no restart), and what chats save goes up. One round now and
 * then, and one right after something is saved; one at a time, on a thread of its own. Without an account, nothing.
 */
public final class Cloud {
    /** For the settings page: connected or not, when the last round worked, why the last one didn't, what went up. */
    public record Status(boolean connected, Instant lastSync, String error, int uploaded, int waiting, Activity now,
                         int retrying, Instant nextRetry, int conflicts, Instant lastVerified) {
        static final Status OFF = new Status(false, null, null, 0, 0, null, 0, null, 0, null);

        public Status(boolean connected, Instant lastSync, String error, int uploaded, int waiting) {
            this(connected, lastSync, error, uploaded, waiting, null, 0, null, 0, null);
        }

        Status doing(Activity a) {
            return new Status(connected, lastSync, error, uploaded, waiting, a, retrying, nextRetry, conflicts, lastVerified);
        }
    }

    /** How often everything uploaded is checked against the account (also on "Verificar ahora"). */
    static final java.time.Duration VERIFY_EVERY = java.time.Duration.ofDays(1);
    private volatile boolean verifyNext;

    /** What a round is doing right now, for the page to say: checking a part, or uploading file {@code done} of {@code total}. */
    public record Activity(Step step, int done, int total, String item) {}

    public enum Step { SETTINGS, LEARNED, UPLOADING, VERIFYING }

    private static final String STYLE_FILE = "writing-style.md";

    private final Supplier<CloudAccount> account;
    private final List<SyncedPart> parts;
    private final SyncedPart settingsPart;
    private final MediaUpload media;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cloud");
        t.setDaemon(true);
        return t;
    });
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService timer;
    private volatile Status status = Status.OFF;
    private int uploaded;

    Cloud(Supplier<CloudAccount> account, SyncedPart settingsPart, SyncedPart stylePart, MediaUpload media) {
        this.account = account;
        this.settingsPart = settingsPart;
        this.parts = List.of(settingsPart, stylePart);
        this.media = media;
    }

    /**
     * @param preferences each chat's options and the folders, as {@code preferences.json} holds them
     * @param adoptPreferences takes the account's in their place, at once
     * @param settingsAdopted after the account's settings were taken: whatever shows them applies them
     * @param mediaFolders where chats save
     */
    public static Cloud create(Path dataDir, Supplier<byte[]> preferences, Predicate<byte[]> adoptPreferences,
                               Runnable settingsAdopted, Supplier<List<Path>> mediaFolders) {
        SyncedPart settings = new SyncedPart(CloudAccount.SETTINGS_FILE, "settings",
                now -> Bundle.of(machine(), now, Settings.get().portable(), Bundle.readPreferences(preferences.get())),
                theirs -> {
                    Settings.get().replacePortable(theirs.settings(), k -> !Bundle.travels(k));
                    if (theirs.preferences() != null) {
                        try {
                            adoptPreferences.test(Bundle.writePreferences(theirs.preferences()));
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    settingsAdopted.run();
                });
        Path style = dataDir.resolve(STYLE_FILE);
        SyncedPart learned = new SyncedPart(CloudAccount.ROOT + "/Ajustes/unichat-ai.json", "ai",
                now -> Bundle.of(machine(), now, Map.of(STYLE_FILE, read(style)), null),
                theirs -> write(style, theirs.settings().getOrDefault(STYLE_FILE, "")));
        return new Cloud(CloudAccount::saved, settings, learned, new MediaUpload(dataDir.resolve("cloud-uploaded.txt"), mediaFolders));
    }

    /** This computer, as the account's files say who wrote them. */
    static String machine() {
        String id = Settings.get().getString("cloud.machine", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            Settings.get().set("cloud.machine", id);
        }
        return id;
    }

    public Status status() { return status; }

    /** Runs on the cloud's thread after each round. */
    public void addListener(Runnable r) { listeners.add(r); }

    public void removeListener(Runnable r) { listeners.remove(r); }

    /** A round every {@code every} from now; a round also right after anything is saved ({@link #syncNow}). */
    public synchronized void start(Duration every) {
        if (timer != null) return;
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cloud-timer");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(this::syncNow, 0, every.toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (timer != null) timer.shutdownNow();
        timer = null;
    }

    /** One round with the account; its result once done. */
    public CompletableFuture<Status> syncNow() {
        return CompletableFuture.supplyAsync(this::round, worker);
    }

    private Status round() {
        CloudAccount a = account.get();
        if (a == null) return publish(Status.OFF);
        Storage dav = a.storage();
        try {
            Instant now = Instant.now();
            if (CloudAccount.settings()) {
                doing(new Activity(Step.SETTINGS, 0, 0, null));
                parts.get(0).sync(dav, now);
                doing(new Activity(Step.LEARNED, 0, 0, null));
                parts.get(1).sync(dav, now);
            }
            if (CloudAccount.media()) {
                uploaded += media.run(dav, (done, total, name) -> doing(new Activity(Step.UPLOADING, done, total, name)));
                if (verifyNext || verifyDue(now)) {
                    verifyNext = false;
                    uploaded += media.verify(dav, (done, total, name) -> doing(new Activity(Step.VERIFYING, done, total, name)));
                    Settings.get().set("cloud.lastVerify", now.toString());
                }
            }
            return publish(finished(now, null));
        } catch (IOException | RuntimeException e) {
            return publish(finished(status.lastSync(), Errors.message(e)));
        }
    }

    private static boolean verifyDue(Instant now) {
        String last = Settings.get().getString("cloud.lastVerify", null);
        return last == null || Instant.parse(last).plus(VERIFY_EVERY).isBefore(now);
    }

    private Status finished(Instant lastSync, String error) {
        int waiting;
        try {
            waiting = media.waiting();
        } catch (IOException ignored) {
            waiting = status.waiting();
        }
        String verified = Settings.get().getString("cloud.lastVerify", null);
        return new Status(true, lastSync, error, uploaded, waiting, null, media.retrying(), media.nextRetry(), media.conflicts(),
                verified == null ? null : Instant.parse(verified));
    }

    /** Checks everything uploaded against the account now, repairing what differs, then the usual round. */
    public CompletableFuture<Status> verifyNow() {
        verifyNext = true;
        return syncNow();
    }

    /** Mid-round: the status as it was, saying what it's doing now. */
    private void doing(Activity a) {
        publish(status.doing(a));
    }

    private Status publish(Status s) {
        status = s;
        for (Runnable r : listeners) r.run();
        return s;
    }

    /** Whether {@code a} answers with this login: the account to keep (pCloud's with its email, to show who), or why not. */
    public static CompletableFuture<CloudAccount> verify(CloudAccount a) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Storage s = a.storage();
                s.check();
                String email = s instanceof PCloudApi p ? p.email() : s instanceof DropboxApi d ? d.email() : null;
                return email == null ? a : new CloudAccount(a.service(), a.url(), email, a.secret());
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
    }

    SyncedPart settingsPart() { return settingsPart; }

    private static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void write(Path file, String text) {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".cloud");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
