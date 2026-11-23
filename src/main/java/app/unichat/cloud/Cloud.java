package app.unichat.cloud;

import app.unichat.settings.Settings;
import app.unichat.support.Errors;
import app.unichat.support.I18n;
import app.unichat.support.SafeFile;
import app.unichat.support.Seen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
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
     * sealed); or this computer's way in was taken away (nothing is sent); or the vault changed in a way nothing this
     * computer holds vouches for (a generation raised through the account: nothing is sent, its key kept, until a change
     * it can follow comes or a way in opens it); or the vault this computer holds is gone from the account with nothing
     * saying it was turned off (its file deleted by hand: nothing is sent, until the person says to go back to plain).
     * Media goes by its own switch in every one.
     */
    public enum VaultState { NONE, LOCKED, DECLINED, OPEN, REMOVED, UNRECOGNIZED, MISSING }

    /**
     * For the settings page: connected or not, when the last round worked, why the last one didn't, what went up, and
     * whether the last round left media waiting because nobody could see Unichat ({@link Seen}).
     */
    public record Status(boolean connected, Instant lastSync, String error, int uploaded, int waiting, Activity now,
                         int retrying, Instant nextRetry, int conflicts, Instant lastVerified, VaultState vault, Moving moving,
                         Notice vaultNotice, boolean mediaPaused) {
        static final Status OFF = new Status(false, null, null, 0, 0);

        @SuppressWarnings("PMD.ExcessiveParameterList") // the record's own components, less the last
        public Status(boolean connected, Instant lastSync, String error, int uploaded, int waiting, Activity now,
                      int retrying, Instant nextRetry, int conflicts, Instant lastVerified, VaultState vault, Moving moving,
                      Notice vaultNotice) {
            this(connected, lastSync, error, uploaded, waiting, now, retrying, nextRetry, conflicts, lastVerified, vault, moving,
                vaultNotice, false);
        }

        public Status(boolean connected, Instant lastSync, String error, int uploaded, int waiting) {
            this(connected, lastSync, error, uploaded, waiting, null, 0, null, 0, null);
        }

        @SuppressWarnings("PMD.ExcessiveParameterList") // the record's own components, before the vault's
        public Status(boolean connected, Instant lastSync, String error, int uploaded, int waiting, Activity now,
                      int retrying, Instant nextRetry, int conflicts, Instant lastVerified) {
            this(connected, lastSync, error, uploaded, waiting, now, retrying, nextRetry, conflicts, lastVerified, VaultState.NONE, Moving.NONE, null);
        }

        Status doing(Activity a) {
            return new Status(connected, lastSync, error, uploaded, waiting, a, retrying, nextRetry, conflicts, lastVerified, vault, moving,
                vaultNotice, mediaPaused);
        }

        /** The same, saying the vault's state is {@code v}; another state than this one's drops its notice, as a round does. */
        Status vault(VaultState v) {
            return v == vault ? this : new Status(connected, lastSync, error, uploaded, waiting, now, retrying, nextRetry, conflicts,
                lastVerified, v, moving, null, mediaPaused);
        }
    }

    /** A part of what the vault carries, as the person knows it, and its file (the one place its name is said). */
    public enum Part {
        SETTINGS("unichat-settings.json"), AI("unichat-ai.json"), SECRETS("unichat-secrets.json"), PINNED("unichat-pinned.json"),
        TRANSCRIPTS("unichat-transcripts.json");

        private final String file;

        Part(String file) {
            this.file = file;
        }

        /** Its logical path in the account. */
        String path() {
            return CloudAccount.SETTINGS_DIR + "/" + file;
        }

        /** The part kept in {@code file}; null for none of ours. */
        static Part of(String file) {
            for (Part p : values()) if (p.file.equals(file)) return p;
            return null;
        }
    }

    /**
     * What the vault left or replaced in a round: its parts that didn't open and went up anew from here, those that didn't
     * open and are left, and the computers whose texts didn't open (machine → its name in the vault, "" when it has none).
     */
    public record Notice(List<Part> replaced, List<Part> damaged, Map<String, String> textsDamaged) {
        public boolean none() {
            return replaced.isEmpty() && damaged.isEmpty() && textsDamaged.isEmpty();
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

    /**
     * The vault's key changed: the new recovery code (shown once; its text form never says it), the ways in that went (each
     * method's name: added again by the person), the files that opened with neither key, left as they were, the computers
     * offered nothing because their key changed since this one saw it (machine → its name in the vault: "the key of … changed"),
     * and whether some file couldn't be sealed again (each computer seals its own again as it takes the new key).
     */
    public record KeyChanged(String code, java.util.Set<String> dropped, List<String> left, Map<String, String> refused, boolean resealFailed) {
        @Override
        public String toString() {
            return "Cloud.KeyChanged[" + dropped + ", " + left.size() + " left]";
        }
    }

    /** How often everything uploaded is checked against the account (also on "Verificar ahora"). */
    static final Duration VERIFY_EVERY = Duration.ofDays(1);

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
    private final MediaTurn mediaTurn;
    private final TextSync texts;
    private final VaultParts vaultParts;
    private final CloudVault vaultJobs = new CloudVault(this);
    private final CloudRenames renames;
    /** The vault's state last read: when it changes, what was seen of the account is another storage's. */
    private VaultState lastVault;
    private final Object vaultLock = new Object();
    /**
     * Held by a round's vault part (everything but media) and by every vault job: a job never runs alongside what reads or
     * writes the vault and the personal parts, and never waits for media (hours: a day's check, a long video). Always
     * taken before {@link #keyLock}, {@link #vaultLock} and {@code TextSync}'s own.
     */
    private final java.util.concurrent.locks.ReentrantLock vaultPart = new java.util.concurrent.locks.ReentrantLock();
    /** How many vault jobs ran, under {@link #vaultPart}: one since a round's vault part means its state is read again. */
    private long vaultJobsDone;
    /** The texts alone never run while the vault's key changes or is forgotten here: they'd go with a key just replaced. */
    private final java.util.concurrent.locks.ReentrantLock keyLock = new java.util.concurrent.locks.ReentrantLock();
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
    /** Ajustes → Nube → Bóveda's jobs, one at a time, apart from the round's thread: they never queue behind its media. */
    private final ExecutorService vaultWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cloud-vault");
        t.setDaemon(true);
        return t;
    });
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    /**
     * A round asked by the timer or by being seen again and not started yet: either asking meanwhile joins it, so a long
     * round has at most one behind it.
     */
    private final java.util.concurrent.atomic.AtomicBoolean roundQueued = new java.util.concurrent.atomic.AtomicBoolean();
    @SuppressWarnings("UnnecessaryLambda") // one instance: removeListener takes away what addListener added
    private final java.util.function.Consumer<Boolean> onSeen = seen -> {
        if (seen) queueRound("the round on being seen");
    };
    private ScheduledExecutorService timer;
    /** A round and a vault job both tell it: what changes a part of it does so in one step ({@code updateAndGet}). */
    private final java.util.concurrent.atomic.AtomicReference<Status> status = new java.util.concurrent.atomic.AtomicReference<>(Status.OFF);
    /** Tests: runs once, after a vault job read the status to tell its state and before it's told (a round may publish meanwhile). */
    volatile Runnable beforeTold;
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
        this.renames = new CloudRenames(texts, keyLock, this::unseen, this::openVault);
        this.textsArrived = textsArrived;
        this.account = account;
        this.settingsPart = settingsPart;
        this.parts = List.of(settingsPart, stylePart);
        this.media = media;
        this.mediaTurn = new MediaTurn(media, Seen.get()::now);
    }

    /**
     * The cloud for the account this computer saved, if any.
     *
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
                            var _ = adoptPreferences.test(Bundle.writePreferences(theirs.preferences())); // false: not a file of them, these stay
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    settingsAdopted.run();
                });
        Path style = dataDir.resolve(STYLE_FILE);
        SyncedPart learned = new SyncedPart(Part.AI.path(), "ai",
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

    public Status status() { return status.get(); }

    /** The vault's state as the last round found it. */
    public VaultState vaultState() { return status().vault(); }

    /** Runs on the cloud's thread after each round. */
    public void addListener(Runnable r) { listeners.add(r); }

    public void removeListener(Runnable r) { listeners.remove(r); }

    /**
     * A round every {@code every} from now; a round also right after anything is saved ({@link #syncNow}), and one when
     * Unichat is seen again, for the media that waited; the timer's and that one share {@link #queueRound}.
     */
    public synchronized void start(Duration every) {
        if (timer != null) return;
        Seen.get().addListener(onSeen);
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cloud-timer");
            t.setDaemon(true);
            return t;
        });
        var _ = timer.scheduleWithFixedDelay(this::tick, 0, every.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** The timer's round, joining one already queued (tests call it directly). */
    void tick() {
        queueRound("the timer's round");
    }

    public synchronized void stop() {
        Seen.get().removeListener(onSeen);
        if (timer != null) timer.shutdownNow();
        timer = null;
    }

    /**
     * The timer's round, or seen again (for the media that waited): one round on the cloud's thread. Seen calls it under
     * its lock, so it takes none: a flag says one is queued and not started, and more asks meanwhile join it (at most one
     * behind a running round). {@code what} names it in the log.
     */
    private void queueRound(String what) {
        if (!roundQueued.compareAndSet(false, true)) return;
        worker.execute(() -> {
            roundQueued.set(false);
            try {
                round();
            } catch (RuntimeException e) {
                System.err.println("Cloud: " + what + ": " + Errors.message(e)); // the next round tries again
            }
        });
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
        Instant now = Instant.now();
        VaultPass pass;
        try {
            pass = vaultPass(dav, now);
        } catch (IOException | RuntimeException e) {
            return publish(finished(status().lastSync(), Errors.message(e), status().vault(), null, mediaTurn.waitsNow(now)));
        }
        String mediaFailed = null;
        boolean check = renames.mayCheck(); // nothing left under media's old name that the check would send again
        try {
            mediaTurn.run(dav, now, progressOf(Step.UPLOADING), progressOf(Step.VERIFYING), check);
        } catch (IOException | RuntimeException e) {
            mediaFailed = Errors.message(e);
        }
        uploaded += mediaTurn.sent(); // a check that failed doesn't take back what went up before it
        if (mediaFailed != null) {
            return publish(finished(status().lastSync(), mediaFailed, vaultAfter(dav, pass), null, mediaTurn.waited()));
        }
        VaultState vault = vaultAfter(dav, pass);
        return publish(finished(now, pass.textsFailed(), vault, vault == pass.state() ? pass.notice() : null, mediaTurn.waited()));
    }

    /** What a round's vault part found: the state, why the texts or the move failed (or null), and what the vault left. */
    private record VaultPass(VaultState state, String textsFailed, Notice notice, long jobsSeen) {}

    /**
     * Everything of a round but media: the vault's state, the personal parts, the texts and the move in, under
     * {@link #vaultPart}, so no vault job runs alongside; its hold on the vault (the key) cleared before media starts.
     */
    private VaultPass vaultPass(Storage dav, Instant now) throws IOException {
        vaultPart.lock();
        try {
            String renameFailed = renames.plain(dav); // before anything reads a folder of the account
            Storage at = VaultNames.at(dav); // the vault under its old names until it's moved
            try (VaultParts.Round vault = stateOf(at)) {
                if (vault.open() && (at != dav || VaultNames.left(dav))) {
                    String[] why = {null};
                    at = renames.vault(dav, at, vault, failed -> why[0] = failed);
                    if (renameFailed == null) renameFailed = why[0];
                    if (at == null) return new VaultPass(vault.state(), renameFailed, null, vaultJobsDone); // with another key now
                }
                if (vault.open()) seePeers(at, vault);
                Storage personal = vault.personal(at);
                if (personal != null && (vault.open() || (CloudAccount.settings() && !renames.settingsWait()))) {
                    doing(new Activity(Step.SETTINGS, 0, 0, null));
                    parts.get(0).sync(personal, now);
                    doing(new Activity(Step.LEARNED, 0, 0, null));
                    parts.get(1).sync(personal, now);
                    if (vault.open()) vaultParts.sync(personal, now);
                }
                String textsFailed = renameFailed;
                Storage textsTo = textsTo(vault, personal);
                if (textsTo != null) {
                    try {
                        syncTexts(textsTo, vault.textsTo()); // small and what a deleted message is read from: before a long video
                    } catch (IOException e) {
                        if (textsFailed == null) textsFailed = Errors.message(e); // told, but the media still go up
                    }
                }
                if (vault.open()) {
                    String moveFailed = moveIn(at, personal, vault.info().id(), now);
                    if (textsFailed == null) textsFailed = moveFailed;
                }
                return new VaultPass(vault.state(), textsFailed, notice(at, vault), vaultJobsDone);
            }
        } finally {
            vaultPart.unlock();
        }
    }

    /**
     * The vault's state to tell once media is over: the vault part's, unless a vault job ran meanwhile (made, opened,
     * turned off…), when it's read again, so a round never tells the state from before the job; that failing, the one
     * last read.
     */
    private VaultState vaultAfter(Storage dav, VaultPass pass) {
        vaultPart.lock();
        try (VaultParts.Round vault = vaultJobsDone == pass.jobsSeen() ? null : stateOf(VaultNames.at(dav))) {
            return vault == null ? pass.state() : vault.state();
        } catch (IOException | RuntimeException e) {
            System.err.println("Cloud: the vault after media: " + Errors.message(e)); // read again next round
            return vaultKnown(pass.state());
        } finally {
            vaultPart.unlock();
        }
    }

    /**
     * The vault's state now, for a round or the texts alone: when it isn't the one last read, every part forgets the
     * stamps it saw, since a plain file and its sealed copy may carry the same.
     */
    VaultParts.Round stateOf(Storage dav) throws IOException {
        VaultParts.Round vault = openVault(dav); // asked outside the lock: the other thread never waits on the network for it
        try {
            synchronized (vaultLock) {
                if (vault.state() != lastVault) {
                    lastVault = vault.state();
                    unseen();
                }
            }
            return vault;
        } catch (IOException | RuntimeException e) {
            vault.close();
            throw e;
        }
    }

    /** Every part reads the account's file afresh: it's another one now, whatever its stamp. */
    private void unseen() throws IOException {
        parts.forEach(SyncedPart::unseen);
        vaultParts.unseen();
        texts.unseen();
    }

    /** The other computers' keys pinned as first seen here ({@link VaultPeers}), for the next change of key; best effort. */
    private static void seePeers(Storage dav, VaultParts.Round vault) {
        byte[] vk = vault.key();
        try {
            VaultPeers.see(dav, vault.info(), vk);
        } catch (IOException | RuntimeException e) {
            System.err.println("Cloud: the vault's computers: " + Errors.message(e)); // seen next round
        } finally {
            Arrays.fill(vk, (byte) 0);
        }
    }

    /** What the vault left or replaced this round; null when nothing. */
    private Notice notice(Storage dav, VaultParts.Round vault) {
        java.util.Set<String> texts = vault.open() ? this.texts.damaged() : java.util.Set.of();
        Map<String, String> named = new java.util.TreeMap<>();
        if (!texts.isEmpty()) {
            Map<String, String> labels;
            try {
                labels = Vault.computers(dav);
            } catch (IOException e) {
                labels = Map.of(); // named by machine alone this round
            }
            for (String machine : texts) named.put(machine, labels.getOrDefault(machine, ""));
        }
        Notice n = new Notice(parts(vault.replaced()), parts(vault.damaged()), named);
        return n.none() ? null : n;
    }

    private static List<Part> parts(List<String> files) {
        return files.stream().map(Part::of).filter(java.util.Objects::nonNull).toList();
    }

    /** The vault's state here ({@link VaultParts#open}), knowing what this computer sent and that its texts' index goes anew. */
    VaultParts.Round openVault(Storage dav) throws IOException {
        return VaultParts.open(dav, this::sentHere, () -> {
            try {
                texts.indexAgain();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    /** Whether a file of the vault is one this computer sent itself: its own texts as it has them, or a part as it agreed on it. */
    private boolean sentHere(String path, byte[] plain) {
        try {
            return texts.sentHere(path, plain) || parts.stream().anyMatch(p -> p.holds(path, plain)) || vaultParts.sentHere(path, plain);
        } catch (IOException e) {
            return false;
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
    private String moveIn(Storage dav, Storage sealed, String vaultId, Instant now) {
        try {
            VaultMove.Result r;
            synchronized (texts) { // TextSync.run's own lock: never alongside textsNow, which writes this computer's texts
                r = VaultMove.run(dav, sealed, vaultId, now);
            }
            Map<String, String> strays = r.strays().isEmpty() ? Map.of() : VaultMove.names(dav, r.strays());
            moving = new Moving(moving.files() + r.moved().size(), r.moved().isEmpty() ? moving.at() : now, strays);
            return r.error();
        } catch (IOException e) {
            return Errors.message(e);
        }
    }

    private void syncTexts(Storage dav, String to) throws IOException {
        java.util.Set<String> came = texts.run(dav, to);
        if (!came.isEmpty()) textsArrived.accept(came);
    }

    /**
     * The texts alone, now, on a thread of their own (a round may be sending a video): what this computer kept goes up,
     * what the others kept comes. Done at once without an account; a failure is the next round's to tell.
     */
    public synchronized CompletableFuture<Void> textsNow() {
        if (textsPending != null) return textsPending;
        CompletableFuture<Void> f = new CompletableFuture<>();
        textsPending = f; // NOPMD: UnusedAssignment, the next caller reads it while the worker runs
        textsWorker.execute(() -> {
            synchronized (this) {
                textsPending = null;
            }
            try {
                CloudAccount a = account.get();
                if (a != null) {
                    keyLock.lock(); // the vault's names read under it: a move of them holds it from start to end
                    try {
                        Storage dav = VaultNames.at(a.storage());
                        try (VaultParts.Round vault = stateOf(dav)) {
                            Storage to = textsTo(vault, vault.personal(dav));
                            if (to != null) syncTexts(to, vault.textsTo());
                        }
                    } finally {
                        keyLock.unlock();
                    }
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("Cloud: texts: " + Errors.message(e));
            }
            f.complete(null);
        });
        return f;
    }

    /** What Ajustes → Nube → Bóveda does with the vault, each job on the vault's thread ({@link CloudVault}). */
    public CloudVault vault() { return vaultJobs; }

    @FunctionalInterface
    interface Job<T> {
        T run(Storage dav) throws IOException;
    }

    /** {@code job}, which only reads the vault or changes nothing of its files and keys, as {@link #onWorker(Job, boolean)} says. */
    <T> CompletableFuture<T> onWorker(Job<T> job) {
        return onWorker(job, false);
    }

    /**
     * {@code job} on the vault's thread, never alongside a round's vault part nor the texts alone, and never waiting for a
     * round's media; a failure completes the future with it. One that {@code writes} the vault's keys or files (a change of
     * key, a turn-off, a way in added or opened) never writes its old names while another computer switches them: the
     * vault is moved first where it's open here, or after where the job opened it ({@link CloudRenames#forJob}); any other
     * sees the vault where it is, and never runs the move (the page opening reads it).
     */
    <T> CompletableFuture<T> onWorker(Job<T> job, boolean writes) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                vaultPart.lock();
                try {
                    CloudAccount a = account.get(); // once it's this job's turn: disconnected meanwhile is no account
                    if (a == null) throw new IOException(I18n.t("cloud.error.vaultNotOpen"));
                    keyLock.lock();
                    try {
                        Storage dav = a.storage(), at = writes ? renames.forJob(dav) : VaultNames.at(dav);
                        T done = job.run(at);
                        if (writes) renames.afterJob(dav, at);
                        return done;
                    } finally {
                        keyLock.unlock();
                    }
                } finally {
                    vaultJobsDone++;
                    vaultPart.unlock();
                    toldAsRead();
                }
            } catch (IOException e) { // NOPMD: ExceptionAsFlowControl, one failure path for no account and a failed job
                throw new java.util.concurrent.CompletionException(e);
            }
        }, vaultWorker);
    }

    /** Whether something waits for the key's lock (tests: the texts alone held behind a round's move of the vault). */
    boolean keyWaiting() {
        return keyLock.hasQueuedThreads();
    }

    /** Whether a vault job waits for a round's vault part to end (tests: the job is asked and held, not yet run). */
    boolean vaultJobWaiting() {
        return vaultPart.hasQueuedThreads();
    }

    /** Done once every vault job asked before it is (tests: nothing of theirs runs after the account goes). */
    CompletableFuture<Void> vaultJobsOver() {
        return CompletableFuture.runAsync(() -> { }, vaultWorker);
    }

    private Status finished(Instant lastSync, String error, VaultState vault, Notice vaultNotice, boolean mediaPaused) {
        int waiting;
        try {
            waiting = media.waiting();
        } catch (IOException ignored) {
            waiting = status().waiting();
        }
        String verified = Settings.get().getString(MediaTurn.LAST_VERIFY, null);
        return new Status(true, lastSync, error, uploaded, waiting, null, media.retrying(), media.nextRetry(), media.conflicts(),
                verified == null ? null : Instant.parse(verified), vault, moving, vaultNotice, mediaPaused);
    }

    /** Checks everything uploaded against the account now, repairing what differs, then the usual round. */
    public CompletableFuture<Status> verifyNow() {
        mediaTurn.verifyAsked();
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

    /**
     * Mid-round: the status as it was, saying what it's doing now and the vault's state last read (a job may have read
     * another since the round's vault part: the page reloads on a state it doesn't show).
     */
    private void doing(Activity a) {
        status.updateAndGet(s -> s.doing(a).vault(vaultKnown(s.vault())));
        told();
    }

    /** The vault's state last read here, by a round, the texts or a job; {@code otherwise} before any. */
    private VaultState vaultKnown(VaultState otherwise) {
        synchronized (vaultLock) {
            return lastVault == null ? otherwise : lastVault;
        }
    }

    /**
     * After a vault job: the state it read told at once, when the status says another (a round may be in its media for
     * hours). In one step: a round telling its end meanwhile is changed in turn, never written over with its middle.
     */
    private void toldAsRead() {
        Status[] was = {null};
        Status now = status.updateAndGet(s -> {
            was[0] = s;
            Status next = s.vault(vaultKnown(s.vault()));
            Runnable hook = beforeTold;
            beforeTold = null;
            if (hook != null) hook.run();
            return next;
        });
        if (now != was[0]) told();
    }

    /** {@code s} told, after a round or as one goes (the listeners told each time, even of the same). */
    private Status publish(Status s) {
        status.set(s);
        told();
        return s;
    }

    private void told() {
        for (Runnable r : listeners) r.run();
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

    TextSync texts() { return texts; }

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
