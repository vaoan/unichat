package app.unichat.cloud;

import app.unichat.settings.Settings;
import app.unichat.support.Errors;
import app.unichat.support.I18n;
import app.unichat.support.SafeFile;

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
     * sealed); or this computer's way in was taken away (nothing is sent). Media goes by its own switch in every one.
     */
    public enum VaultState { NONE, LOCKED, DECLINED, OPEN, REMOVED }

    /** For the settings page: connected or not, when the last round worked, why the last one didn't, what went up. */
    public record Status(boolean connected, Instant lastSync, String error, int uploaded, int waiting, Activity now,
                         int retrying, Instant nextRetry, int conflicts, Instant lastVerified, VaultState vault, Moving moving,
                         Notice vaultNotice) {
        static final Status OFF = new Status(false, null, null, 0, 0);

        public Status(boolean connected, Instant lastSync, String error, int uploaded, int waiting) {
            this(connected, lastSync, error, uploaded, waiting, null, 0, null, 0, null);
        }

        public Status(boolean connected, Instant lastSync, String error, int uploaded, int waiting, Activity now,
                      int retrying, Instant nextRetry, int conflicts, Instant lastVerified) {
            this(connected, lastSync, error, uploaded, waiting, now, retrying, nextRetry, conflicts, lastVerified, VaultState.NONE, Moving.NONE, null);
        }

        Status doing(Activity a) {
            return new Status(connected, lastSync, error, uploaded, waiting, a, retrying, nextRetry, conflicts, lastVerified, vault, moving,
                vaultNotice);
        }
    }

    /** A part of what the vault carries, as the person knows it. */
    public enum Part {
        SETTINGS("unichat-settings.json"), AI("unichat-ai.json"), SECRETS("unichat-secrets.json"), PINNED("unichat-pinned.json"),
        TRANSCRIPTS("unichat-transcripts.json");

        private final String file;

        Part(String file) {
            this.file = file;
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
    /** The texts alone never run while the vault's key changes or is forgotten here: they'd go with a key just replaced. */
    private final Object keyLock = new Object();
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
            if (vault.open()) seePeers(dav, vault);
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
                    syncTexts(textsTo, vault.textsTo()); // small and what a deleted message is read from: before a long video
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
            return publish(finished(now, textsFailed, vault.state(), notice(dav, vault)));
        } catch (IOException | RuntimeException e) {
            return publish(finished(status.lastSync(), Errors.message(e), status.vault(), null));
        }
    }

    /**
     * The vault's state now, for a round or the texts alone: when it isn't the one last read, every part forgets the
     * stamps it saw, since a plain file and its sealed copy may carry the same.
     */
    private VaultParts.Round vault(Storage dav) throws IOException {
        VaultParts.Round vault = openVault(dav); // asked outside the lock: the other thread never waits on the network for it
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
    private VaultParts.Round openVault(Storage dav) throws IOException {
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
        textsPending = f;
        textsWorker.execute(() -> {
            synchronized (this) {
                textsPending = null;
            }
            try {
                CloudAccount a = account.get();
                if (a != null) {
                    Storage dav = a.storage();
                    synchronized (keyLock) {
                        try (VaultParts.Round vault = vault(dav)) {
                            Storage to = textsTo(vault, vault.personal(dav));
                            if (to != null) syncTexts(to, vault.textsTo());
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("Cloud: texts: " + Errors.message(e));
            }
            f.complete(null);
        });
        return f;
    }

    /**
     * A new key for the vault ({@link Vault#rotate}), on the cloud's thread: only with the vault open here. This computer
     * then takes it at once, as the others do on their next round; if that fails, its next round does it.
     */
    CompletableFuture<KeyChanged> rotateKey() {
        return onWorker(dav -> rotate(dav, id -> true));
    }

    /**
     * The same, the new key offered only to the computers in {@code confirmed} (those the person saw listed and said yes
     * to) and this one; any other, one that came after the list included, gets nothing and is named in {@link KeyChanged#refused}.
     */
    public CompletableFuture<KeyChanged> rotateKey(java.util.Set<String> confirmed) {
        java.util.Set<String> asked = java.util.Set.copyOf(confirmed);
        return onWorker(dav -> rotate(dav, asked::contains));
    }

    /**
     * Another computer taken out of the vault and the key changed, in one: a removed computer still holds the key, so
     * only a new one shuts it out of anything new. Kept out first, so it's never offered the new key.
     */
    public CompletableFuture<KeyChanged> removeAndChangeKey(String machine, java.util.Set<String> confirmed) {
        java.util.Set<String> asked = java.util.Set.copyOf(confirmed);
        return onWorker(dav -> {
            VaultActions.remove(dav, machine);
            try {
                return rotate(dav, id -> !id.equals(machine) && asked.contains(id));
            } catch (IOException | RuntimeException e) {
                // Removed all the same: the person must know the key it holds still opens the vault.
                throw new IOException(I18n.t("cloud.error.vaultRemovedKeyKept", Errors.message(e)), e);
            }
        });
    }

    private KeyChanged rotate(Storage dav, java.util.function.Predicate<String> confirmed) throws IOException {
        Vault.Rotated r = withKey(dav, (vault, old) -> {
            VaultPeers peers = VaultPeers.load(vault.info().id());
            try {
                return Vault.rotate(dav, old, machine(), peers, confirmed);
            } finally {
                peers.save(); // a computer seen for the first time is pinned, the change done or not
            }
        });
        Arrays.fill(r.vk(), (byte) 0);
        try {
            openVault(dav).close();
        } catch (IOException | RuntimeException e) {
            System.err.println("Cloud: the vault's new key: " + Errors.message(e)); // the next round takes it
        }
        Vault.Info now = Vault.info(dav);
        Settings.get().set(VaultActions.UNCONFIRMED, now == null ? null : now.id()); // a new code, to be confirmed as kept
        return new KeyChanged(r.code().shown(), r.dropped().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()), r.left(), r.refused(), r.resealFailed());
    }

    /**
     * The person says {@code machine}'s key, as its slot shows it now, is that computer's (it was set up again): pinned in
     * place of what this computer saw, so the next change of key offers it one. Only with the vault open here, and only a
     * key the vault key sealed.
     */
    public CompletableFuture<Void> acceptKey(String machine) {
        return onWorker(dav -> {
            if (!Ids.ours(machine)) throw new IllegalArgumentException("Not a machine id");
            return withKey(dav, (vault, vk) -> {
                VaultSlot slot = Vault.slot(dav, vault.info(), machine);
                byte[] key = slot == null || slot.method() != VaultSlot.Method.COMPUTER ? null : VaultNext.publicKey(slot, vk);
                if (key == null) throw new IOException(I18n.t("cloud.error.vaultNoSuchKey"));
                VaultPeers peers = VaultPeers.load(vault.info().id());
                peers.accept(machine, key);
                peers.save();
                return null;
            });
        });
    }

    /** Another computer's way into the vault taken away, on the cloud's thread; this one's own goes by {@link #forgetHere}. */
    public CompletableFuture<Void> removeComputer(String machine) {
        return onWorker(dav -> {
            VaultActions.remove(dav, machine);
            return null;
        });
    }

    /**
     * The vault forgotten on this computer, on the cloud's thread: its slot deleted, then its hold on the vault here (whether
     * it declined is left as it is). The next round finds it locked; it can be opened again later.
     */
    public CompletableFuture<Void> forgetHere() {
        return onWorker(dav -> {
            Vault.removeSlot(dav, machine());
            VaultParts.forget();
            return null;
        });
    }

    /** What Ajustes → Nube → Bóveda shows: the vault as it stands here, its ways in and, open, its computers. */
    public CompletableFuture<VaultView> vaultView() {
        return onWorker(dav -> {
            try (VaultParts.Round vault = vault(dav)) {
                return VaultActions.view(dav, vault);
            }
        });
    }

    /** A new vault, open here; its recovery code as printed, shown once and never kept. */
    public CompletableFuture<String> createVault() {
        return onWorker(VaultActions::create);
    }

    /**
     * The vault opened here with a way in ({@code secret}: the recovery code as typed, or the password). A
     * secret that opens nothing fails with "wrong" and writes nothing. {@code secret} is cleared once used.
     */
    public CompletableFuture<Void> openVault(VaultView.Way way, char[] secret) {
        CompletableFuture<Void> f = onWorker(dav -> {
            VaultActions.open(dav, way, secret);
            return null;
        });
        f.whenComplete((r, e) -> Arrays.fill(secret, '\0'));
        return f;
    }

    /** "Seguir sin la bóveda en este equipo" ({@code true}), or taken back. */
    public CompletableFuture<Void> declineVault(boolean declined) {
        return onWorker(dav -> {
            VaultActions.decline(declined);
            return null;
        });
    }

    /** A password as one more way in, only with the vault open here; {@code password} is cleared once used. */
    public CompletableFuture<Void> addPassword(char[] password) {
        CompletableFuture<Void> f = onWorker(dav -> withKey(dav, (vault, vk) -> {
            VaultActions.addPassword(dav, vk, password);
            return null;
        }));
        f.whenComplete((r, e) -> Arrays.fill(password, '\0'));
        return f;
    }

    /** A new recovery code in place of the old one (which no longer opens it), only with the vault open here. */
    public CompletableFuture<String> newRecoveryCode() {
        return onWorker(dav -> withKey(dav, (vault, vk) -> VaultActions.newRecovery(dav, vk, vault.info())));
    }

    /** The person typed back the groups asked of the latest recovery code: kept. */
    public CompletableFuture<Void> codeKept() {
        return onWorker(dav -> {
            VaultActions.codeKept();
            return null;
        });
    }

    private interface KeyJob<T> {
        T run(VaultParts.Round vault, byte[] vk) throws IOException;
    }

    /** {@code job} with the vault open here and a copy of its key, cleared after; not open → "the vault isn't open". */
    private <T> T withKey(Storage dav, KeyJob<T> job) throws IOException {
        try (VaultParts.Round vault = openVault(dav)) {
            if (!vault.open()) throw new IOException(I18n.t("cloud.error.vaultNotOpen"));
            byte[] vk = vault.key();
            try {
                return job.run(vault, vk);
            } finally {
                Arrays.fill(vk, (byte) 0);
            }
        }
    }

    private interface Job<T> {
        T run(Storage dav) throws IOException;
    }

    /** {@code job} on the cloud's thread, never alongside a round or the texts alone; a failure completes the future with it. */
    private <T> CompletableFuture<T> onWorker(Job<T> job) {
        return CompletableFuture.supplyAsync(() -> {
            CloudAccount a = account.get();
            try {
                if (a == null) throw new IOException(I18n.t("cloud.error.vaultNotOpen"));
                synchronized (keyLock) {
                    return job.run(a.storage());
                }
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, worker);
    }

    private static boolean verifyDue(Instant now) {
        String last = Settings.get().getString("cloud.lastVerify", null);
        return last == null || Instant.parse(last).plus(VERIFY_EVERY).isBefore(now);
    }

    private Status finished(Instant lastSync, String error, VaultState vault, Notice vaultNotice) {
        int waiting;
        try {
            waiting = media.waiting();
        } catch (IOException ignored) {
            waiting = status.waiting();
        }
        String verified = Settings.get().getString("cloud.lastVerify", null);
        return new Status(true, lastSync, error, uploaded, waiting, null, media.retrying(), media.nextRetry(), media.conflicts(),
                verified == null ? null : Instant.parse(verified), vault, moving, vaultNotice);
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
