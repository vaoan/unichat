package app.unichat.cloud;

import app.unichat.settings.Settings;
import app.unichat.support.Errors;
import app.unichat.support.SafeFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    /**
     * The vault, as this computer stands with it: none in the account (today's plain round); there but not opened here, or
     * this computer chose to go on without it (nothing personal goes up, plain or sealed); open (everything personal goes
     * sealed); or this computer's way in was taken away (nothing is sent). Media goes by its own switch in every one.
     */
    public enum VaultState { NONE, LOCKED, DECLINED, OPEN, REMOVED }

    /** For the settings page: connected or not, when the last round worked, why the last one didn't, what went up. */
    public record Status(boolean connected, Instant lastSync, String error, int uploaded, int waiting, Activity now,
                         int retrying, Instant nextRetry, int conflicts, Instant lastVerified, VaultState vault, Moving moving) {
        static final Status OFF = new Status(false, null, null, 0, 0);

        public Status(boolean connected, Instant lastSync, String error, int uploaded, int waiting) {
            this(connected, lastSync, error, uploaded, waiting, null, 0, null, 0, null);
        }

        public Status(boolean connected, Instant lastSync, String error, int uploaded, int waiting, Activity now,
                      int retrying, Instant nextRetry, int conflicts, Instant lastVerified) {
            this(connected, lastSync, error, uploaded, waiting, now, retrying, nextRetry, conflicts, lastVerified, VaultState.NONE, Moving.NONE);
        }

        Status doing(Activity a) {
            return new Status(connected, lastSync, error, uploaded, waiting, a, retrying, nextRetry, conflicts, lastVerified, vault, moving);
        }
    }

    /**
     * What came into the vault from the plain copies here (how many files, and when the last did), and the computers still
     * sending plain, each machine with its computer's name in the vault ("" for one that has none there).
     */
    public record Moving(int files, Instant at, Map<String, String> strays) {
        static final Moving NONE = new Moving(0, null, Map.of());

        /** A stray's name to show: its computer's in the vault, "" when unknown. */
        public String strayName(String machine) {
            return strays.getOrDefault(machine, "");
        }
    }

    /** How often everything uploaded is checked against the account (also on "Verificar ahora"). */
    static final java.time.Duration VERIFY_EVERY = java.time.Duration.ofDays(1);
    private volatile boolean verifyNext;

    /** What a round is doing right now, for the page to say: checking a part, or uploading file {@code done} of {@code total}. */
    public record Activity(Step step, int done, int total, String item, long sent, long size, long bytesPerSecond) {
        public Activity(Step step, int done, int total, String item) {
            this(step, done, total, item, 0, 0, 0);
        }
    }

    public enum Step { SETTINGS, LEARNED, UPLOADING, VERIFYING }

    private static final String STYLE_FILE = "writing-style.md";

    private final Supplier<CloudAccount> account;
    private final List<SyncedPart> parts;
    private final SyncedPart settingsPart;
    private final MediaUpload media;
    private final TextSync texts;
    private final VaultParts vaultParts;
    /** The vault's state last read: when it changes, what was seen of the account is another storage's. */
    private VaultState lastVault;
    private final Object vaultLock = new Object();
    private final java.util.function.Consumer<java.util.Set<String>> textsArrived;
    /** Asking for the texts alone, apart from the round: a round may be busy sending a video for minutes. */
    private final ExecutorService textsWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cloud-texts");
        t.setDaemon(true);
        return t;
    });
    /** The texts' turn asked and not started yet: more asks meanwhile join it. */
    private CompletableFuture<Void> textsPending;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cloud");
        t.setDaemon(true);
        return t;
    });
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService timer;
    private volatile Status status = Status.OFF;
    private int uploaded;
    private Moving moving = Moving.NONE;

    /**
     * What chats say, kept on every computer ({@link TextSync}).
     *
     * @param dir where they're kept here
     * @param made a file in a chat's folder made from them (their readable copy): it doesn't go up as media
     * @param arrived told the chats (their keys) that something came for from another computer
     */
    public record Texts(Path dir, Predicate<Path> made, java.util.function.Consumer<java.util.Set<String>> arrived) {}

    /**
     * What else is personal, which only the vault carries ({@link VaultParts}).
     *
     * @param pins the pinned messages, as {@code pinned-messages.json} holds them
     * @param adoptPins takes another computer's in their place, at once
     * @param transcripts the voice notes' transcripts' folder
     */
    public record Personal(Supplier<byte[]> pins, java.util.function.Consumer<byte[]> adoptPins, Path transcripts) {}

    Cloud(Supplier<CloudAccount> account, SyncedPart settingsPart, SyncedPart stylePart, MediaUpload media, TextSync texts,
          java.util.function.Consumer<java.util.Set<String>> textsArrived, VaultParts vaultParts) {
        this.texts = texts;
        this.vaultParts = vaultParts;
        this.textsArrived = textsArrived;
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
     * @param texts what chats say
     * @param personal what only the vault carries
     */
    public static Cloud create(Path dataDir, Supplier<byte[]> preferences, Predicate<byte[]> adoptPreferences,
                               Runnable settingsAdopted, Supplier<List<Path>> mediaFolders, Texts texts, Personal personal) {
        return create(CloudAccount::saved, dataDir, preferences, adoptPreferences, settingsAdopted, mediaFolders, texts, personal);
    }

    /** The same with the account given (tests: one that isn't this computer's saved one). */
    static Cloud create(Supplier<CloudAccount> account, Path dataDir, Supplier<byte[]> preferences, Predicate<byte[]> adoptPreferences,
                        Runnable settingsAdopted, Supplier<List<Path>> mediaFolders, Texts texts, Personal personal) {
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
        return new Cloud(account, settings, learned,
                new MediaUpload(dataDir.resolve("cloud-uploaded.txt"), mediaFolders, texts.made()),
                new TextSync(texts.dir(), dataDir.resolve("cloud-texts.json"), machine()), texts.arrived(),
                new VaultParts(personal.pins(), personal.adoptPins(), personal.transcripts()));
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

    /** The vault's state as the last round found it. */
    public VaultState vaultState() { return status.vault(); }

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

    /**
     * Without a vault, today's round: the settings and what the AI learned by their switch, the texts by media's. With it
     * open here, all of that and the vault's own parts, sealed, whatever media's switch says. Locked, declined or removed
     * here: nothing personal. Media by its own switch in all of them.
     */
    private Status round() {
        CloudAccount a = account.get();
        if (a == null) return publish(Status.OFF);
        Storage dav = a.storage();
        try (VaultParts.Round vault = vault(dav)) {
            Instant now = Instant.now();
            Storage personal = vault.personal(dav);
            if (personal != null && (vault.open() || CloudAccount.settings())) {
                doing(new Activity(Step.SETTINGS, 0, 0, null));
                parts.get(0).sync(personal, now);
                doing(new Activity(Step.LEARNED, 0, 0, null));
                parts.get(1).sync(personal, now);
                if (vault.open()) vaultParts.sync(personal, now);
            }
            String textsFailed = null;
            Storage textsTo = textsTo(vault, personal);
            if (textsTo != null) {
                try {
                    syncTexts(textsTo); // small and what a deleted message is read from: before a long video
                } catch (IOException e) {
                    textsFailed = Errors.message(e); // told, but the media still go up
                }
            }
            if (vault.open()) {
                String moveFailed = moveIn(dav, personal, now);
                if (textsFailed == null) textsFailed = moveFailed;
            }
            if (CloudAccount.media()) {
                uploaded += media.run(dav, progressOf(Step.UPLOADING));
                if (verifyNext || verifyDue(now)) {
                    verifyNext = false;
                    uploaded += media.verify(dav, progressOf(Step.VERIFYING));
                    Settings.get().set("cloud.lastVerify", now.toString());
                }
            }
            return publish(finished(now, textsFailed, vault.state()));
        } catch (IOException | RuntimeException e) {
            return publish(finished(status.lastSync(), Errors.message(e), status.vault()));
        }
    }

    /**
     * The vault's state now, for a round or the texts alone: when it isn't the one last read, every part forgets the
     * stamps it saw, since a plain file and its sealed copy may carry the same.
     */
    private VaultParts.Round vault(Storage dav) throws IOException {
        VaultParts.Round vault = VaultParts.open(dav); // asked outside the lock: the other thread never waits on the network for it
        try {
            synchronized (vaultLock) {
                if (vault.state() != lastVault) {
                    lastVault = vault.state();
                    parts.forEach(SyncedPart::unseen);
                    vaultParts.unseen();
                    texts.unseen();
                }
            }
            return vault;
        } catch (IOException | RuntimeException e) {
            vault.close();
            throw e;
        }
    }

    /** The texts go sealed while the vault is open here, plain by media's switch without one, and nowhere otherwise. */
    private static Storage textsTo(VaultParts.Round vault, Storage personal) {
        return personal != null && (vault.open() || CloudAccount.media()) ? personal : null;
    }

    /**
     * What's still plain goes into the vault, after the vault's own parts and texts went (so they're never copied over),
     * and who still sends plain is named; why it couldn't, or null.
     */
    private String moveIn(Storage dav, Storage sealed, Instant now) {
        try {
            VaultMove.Result r;
            synchronized (texts) { // TextSync.run's own lock: never alongside textsNow, which writes this computer's texts
                r = VaultMove.run(dav, sealed, now);
            }
            Map<String, String> strays = r.strays().isEmpty() ? Map.of() : VaultMove.names(dav, r.strays());
            moving = new Moving(moving.files() + r.moved().size(), r.moved().isEmpty() ? moving.at() : now, strays);
            return r.error();
        } catch (IOException e) {
            return Errors.message(e);
        }
    }

    private void syncTexts(Storage dav) throws IOException {
        java.util.Set<String> came = texts.run(dav);
        if (!came.isEmpty()) textsArrived.accept(came);
    }

    /**
     * The texts alone, now, on a thread of their own (a round may be sending a video): what this computer kept goes up,
     * what the others kept comes. Done at once without an account; a failure is the next round's to tell.
     */
    public synchronized CompletableFuture<Void> textsNow() {
        if (textsPending != null) return textsPending;
        CompletableFuture<Void> f = new CompletableFuture<>();
        textsPending = f;
        textsWorker.execute(() -> {
            synchronized (this) {
                textsPending = null;
            }
            try {
                CloudAccount a = account.get();
                if (a != null) {
                    Storage dav = a.storage();
                    try (VaultParts.Round vault = vault(dav)) {
                        Storage to = textsTo(vault, vault.personal(dav));
                        if (to != null) syncTexts(to);
                    }
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("Cloud: texts: " + Errors.message(e));
            }
            f.complete(null);
        });
        return f;
    }

    private static boolean verifyDue(Instant now) {
        String last = Settings.get().getString("cloud.lastVerify", null);
        return last == null || Instant.parse(last).plus(VERIFY_EVERY).isBefore(now);
    }

    private Status finished(Instant lastSync, String error, VaultState vault) {
        int waiting;
        try {
            waiting = media.waiting();
        } catch (IOException ignored) {
            waiting = status.waiting();
        }
        String verified = Settings.get().getString("cloud.lastVerify", null);
        return new Status(true, lastSync, error, uploaded, waiting, null, media.retrying(), media.nextRetry(), media.conflicts(),
                verified == null ? null : Instant.parse(verified), vault, moving);
    }

    /** Checks everything uploaded against the account now, repairing what differs, then the usual round. */
    public CompletableFuture<Status> verifyNow() {
        verifyNext = true;
        return syncNow();
    }

    /**
     * Files as they're handled, with their bytes as they go out and the round's speed (bytes since the round's first
     * byte over the time since): told at most a few times a second.
     */
    private MediaUpload.Progress progressOf(Step step) {
        long[] round = {0, 0, 0}; // bytes of finished files, the file in progress, when the first byte went (ns)
        long[] told = {0};
        Activity[] at = {null};
        return new MediaUpload.Progress() {
            @Override public void uploading(int done, int total, String name) {
                round[0] += round[1];
                round[1] = 0;
                at[0] = new Activity(step, done, total, name);
                doing(at[0]);
            }

            @Override public void sent(long bytes, long size) {
                long now = System.nanoTime();
                if (round[2] == 0) round[2] = now;
                round[1] = bytes;
                if (now - told[0] < 250_000_000L && bytes < size) return;
                told[0] = now;
                double secs = Math.max(0.001, (now - round[2]) / 1e9);
                Activity a = at[0];
                if (a != null) doing(new Activity(a.step(), a.done(), a.total(), a.item(), bytes, size, (long) ((round[0] + bytes) / secs)));
            }
        };
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

    static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    static void write(Path file, String text) {
        try {
            SafeFile.at(file, ".cloud").write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
