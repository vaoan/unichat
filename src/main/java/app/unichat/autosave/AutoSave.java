package app.unichat.autosave;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.model.SaveKind;
import app.unichat.service.ChatPreferences;
import app.unichat.service.ChatReader;
import app.unichat.service.ChatService;
import app.unichat.service.ChatSession;
import app.unichat.service.MediaSource;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import app.unichat.support.MarkOfTheWeb;
import app.unichat.support.Urls;
import app.unichat.video.Ffmpeg;
import app.unichat.video.YtDlp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Saves what others send in a chat that has it on (its options, {@link ChatPreferences#setAutoSave}), each kind chosen
 * apart ({@link SaveKind}): photos, videos, audio and voice notes, documents, GIFs (never stickers), and the video
 * behind each link ({@link YtDlp}). As soon as a message is there, the chat opened or not: every change to the chat's
 * messages (a sync, live or catching up after sleep or a restart) saves what's newer than the last one saved
 * ({@link SavedUpTo}), so what disappears later (view once, timers) is kept and history from before stays out. Into
 * the chat's folder ({@link SaveFolders}), one file at a time on a thread of its own; nothing saved is overwritten.
 */
public final class AutoSave implements ChatService.Listener {
    /** What saving does, apart so tests don't need a server or yt-dlp. */
    interface Saver {
        /** The file behind {@code mediaUrl}, downloaded. */
        /** {@code progress}: how much has arrived, 0 to 1, when that's known. */
        Path media(String mediaUrl, java.util.function.DoubleConsumer progress) throws IOException, InterruptedException;

        /** The video behind {@code url} saved into {@code dir}: null, or why not. */
        /** {@code tag}: the message's id, which goes in the name ({@link FileNames#tag}). */
        String link(String url, Path dir, String tag, java.util.function.DoubleConsumer progress) throws IOException, InterruptedException;
    }

    /** What saving is doing, for the menu-bar icon and the notices. Called on the saving thread. */
    public interface Progress {
        /** {@code done} of {@code total} in the round going on; total 0: nothing going on. */
        void changed(int done, int total);

        /** A round ended with {@code saved} files saved; {@code chat} is where the last came from. */
        void finished(int saved, Chat chat);

        /** {@code what} (a file's name, a link) from {@code chat} couldn't be saved, because {@code why}. */
        void failed(String what, Chat chat, String why);
    }

    private static final Map<ChatSession, AutoSave> BY_SERVICE = new WeakHashMap<>();

    private final ChatPreferences prefs;
    private final ChatReader chats;
    private final SavedUpTo savedUpTo;
    private final Saver saver;
    private volatile Progress progress = new Progress() {
        @Override public void changed(int done, int total) { }
        @Override public void finished(int saved, Chat chat) { }
        @Override public void failed(String what, Chat chat, String why) { }
    };
    /** The round going on: only the saving thread touches them. */
    private int total;
    private int done;
    private int savedInRound;
    private Chat lastChat;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "autosave");
        t.setDaemon(true);
        return t;
    });

    AutoSave(ChatPreferences prefs, ChatReader chats, SavedUpTo savedUpTo, Saver saver) {
        this.prefs = prefs;
        this.chats = chats;
        this.savedUpTo = savedUpTo;
        this.saver = saver;
    }

    /** The one for {@code svc}, listening from the first call: it's started with the service, before its first sync. */
    public static synchronized AutoSave of(ChatService svc) {
        return BY_SERVICE.computeIfAbsent(svc, s -> {
            AutoSave a = new AutoSave(svc, svc, new SavedUpTo(Settings.dataDir().resolve("autosave.json")),
                    downloading(svc));
            svc.addListener(a);
            return a;
        });
    }

    /** The folders of every chat that saves something: what goes up to the person's cloud. */
    public List<Path> folders() {
        return chats.chats().stream().filter(c -> !Wanted.of(prefs, c.id()).none()).map(SaveFolders::of).distinct().toList();
    }

    /** Where a chat's things go ({@link SaveFolders}). */
    public Path folder(Chat chat) {
        return SaveFolders.of(chat);
    }

    /**
     * A new base folder (null: the default), taking along each chat's folder under the old one (never the base whole:
     * it can hold the person's own things); on the saving thread, so nothing is written meanwhile. Gives how many files
     * moved.
     */
    public java.util.concurrent.CompletableFuture<Integer> setBase(Path folder) {
        return onWorker(() -> {
            Path before = SaveFolders.base();
            SaveFolders.setBase(folder);
            Path after = SaveFolders.base();
            int moved = 0;
            for (Chat c : chats.chats()) {
                if (SaveFolders.hasOwn(c.id())) continue;
                String name = FileNames.folder(c.title());
                moved += SavedMove.move(before.resolve(name), after.resolve(name));
            }
            return moved;
        });
    }

    /** A chat's own folder (null: under the base again), taking along what it saved where it was. */
    public java.util.concurrent.CompletableFuture<Integer> setOwn(Chat chat, Path folder) {
        return onWorker(() -> {
            Path before = SaveFolders.of(chat);
            SaveFolders.setOwn(chat.id(), folder);
            return SavedMove.move(before, SaveFolders.of(chat));
        });
    }

    private java.util.concurrent.CompletableFuture<Integer> onWorker(java.util.concurrent.Callable<Integer> work) {
        java.util.concurrent.CompletableFuture<Integer> f = new java.util.concurrent.CompletableFuture<>();
        worker.execute(() -> {
            try {
                f.complete(work.call());
            } catch (Exception e) {
                f.completeExceptionally(e);
            }
        });
        return f;
    }

    public void setProgress(Progress p) {
        progress = p;
    }

    /** Turned on just now: from here on, not what was there before. */
    public void startNow(String chatId) {
        worker.execute(() -> savedUpTo.startNow(chatId));
    }

    @Override
    public void messagesChanged(String chatId) {
        worker.execute(() -> catchUp(chatId));
    }

    /** Saves, in order, what was sent in {@code chatId} since the last one saved (others', and mine if chosen). Worker. */
    private void catchUp(String chatId) {
        Wanted wanted = Wanted.of(prefs, chatId);
        Chat chat = chats.chat(chatId);
        if (wanted.none() || chat == null) return;
        List<Message> fresh = chats.messages(chatId).stream()
                .filter(m -> !m.deleted() && m.timestamp() != null && !wanted.of(m).isEmpty() && savedUpTo.isNew(chatId, m))
                .sorted(Comparator.comparing(Message::timestamp)).toList();
        Path dir = folder(chat);
        for (Message m : fresh) total += work(m, wanted.of(m));
        if (total > done) progress.changed(done, total);
        for (Message m : fresh) {
            Set<SaveKind> kinds = wanted.of(m);
            SaveKind kind = SaveKind.of(m); // null: a sticker, or no media
            if (kind != null && kinds.contains(kind)) step(chat, saveMedia(m, chat, dir));
            if (kinds.contains(SaveKind.LINKS)) for (String url : links(m)) step(chat, saveLink(url, m, chat, dir));
            savedUpTo.saved(chatId, m);
        }
    }

    /** How many things {@code m} gives to save. */
    private static int work(Message m, Set<SaveKind> kinds) {
        SaveKind kind = SaveKind.of(m);
        return (kind != null && kinds.contains(kind) ? 1 : 0) + (kinds.contains(SaveKind.LINKS) ? links(m).size() : 0);
    }

    /** One thing done, saved or not; the round ends when nothing's left. */
    private void step(Chat chat, boolean saved) {
        done++;
        if (saved) {
            savedInRound++;
            lastChat = chat;
        }
        progress.changed(done, total);
        if (done < total) return;
        if (savedInRound > 0) progress.finished(savedInRound, lastChat);
        total = done = savedInRound = 0;
        lastChat = null;
        progress.changed(0, 0);
    }

    /** The links in what it says, and the page a reel shown as a picture points to. */
    static Set<String> links(Message m) {
        Set<String> out = new LinkedHashSet<>(Urls.in(m.body()));
        // A video that came with its page (Instagram's reels) is that page's video: downloading the page again saved
        // the same reel twice, the second one in VP9, which a Mac doesn't open.
        if (m.externalUrl() != null && m.externalUrl().startsWith("http") && m.type() != app.unichat.model.MessageType.VIDEO) out.add(m.externalUrl());
        return out;
    }

    private boolean saveMedia(Message m, Chat chat, Path dir) {
        String name = FileNames.forMessage(m, ZoneId.systemDefault()); // what a notice says: the name as sent
        Path[] file = {null};
        try {
            copyInto(m, dir, file, done -> { });
            return true;
        } catch (IOException | RuntimeException e) {
            return failed(name, chat, why(e, dir, file[0]));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Copies {@code m}'s file into {@code dir} under {@link FileNames#forSaving}, unless it's there already. Written
     * under a hidden name and renamed once whole: nothing (the cloud, the person) ever takes a half-written file.
     * {@code downloaded[0]}: the cached file, for telling why it failed. False: it was there already.
     */
    private boolean copyInto(Message m, Path dir, Path[] downloaded, java.util.function.DoubleConsumer progress)
            throws IOException, InterruptedException {
        if (FileNames.savedIn(dir, m)) return false;
        downloaded[0] = saver.media(m.mediaUrl(), progress);
        Files.createDirectories(dir);
        Path target = FileNames.free(dir, FileNames.forSaving(m, ZoneId.systemDefault()));
        Path part = dir.resolve("." + target.getFileName() + ".part");
        Files.copy(downloaded[0], part, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
        MarkOfTheWeb.mark(target);
        return true;
    }

    private boolean saveLink(String url, Message m, Chat chat, Path dir) {
        try {
            String why = saver.link(url, dir, FileNames.tag(m), done -> { });
            return why == null || failed(url, chat, why);
        } catch (IOException | RuntimeException e) {
            return failed(url, chat, why(e, dir, null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Tells and logs it; false, as nothing was saved. */
    private boolean failed(String what, Chat chat, String why) {
        System.err.println("Auto-save: " + what + " from " + chat.id() + " not saved: " + why);
        progress.failed(what, chat, why);
        return false;
    }

    /**
     * Why it failed, as a person reads it. No room is told by the space left where it was going (the system's own
     * words differ on each one), a folder that can't be written by the kind of error; else the error's own words.
     */
    static String why(Exception e, Path dir, Path file) {
        try {
            long needed = file != null && Files.isRegularFile(file) ? Files.size(file) : 0;
            Path existing = dir;
            while (existing != null && !Files.exists(existing)) existing = existing.getParent();
            if (existing != null && Files.getFileStore(existing).getUsableSpace() <= Math.max(needed, 1 << 20)) {
                return I18n.t("autosave.error.noSpace");
            }
        } catch (IOException ignored) {
            // can't tell the space: the error's own words below
        }
        if (e instanceof java.nio.file.AccessDeniedException) return I18n.t("autosave.error.denied", dir);
        Throwable cause = e.getCause() != null && e.getMessage() != null && e.getMessage().startsWith("java.") ? e.getCause() : e;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    /**
     * Exploring a chat's past, for its options: going on or not, back to when everything was looked at, whether that's
     * the beginning, what this run looked at, saved and found gone, and what it's doing right now (null when not
     * running): without that, "Buscando…" for minutes said nothing.
     */
    public record Exploring(boolean running, Instant back, boolean start, int saved, int unavailable, int looked, Now now) {
        Exploring counted(boolean ok) {
            return new Exploring(running, back, start, saved + (ok ? 1 : 0), unavailable + (ok ? 0 : 1), looked, now);
        }
    }

    /**
     * What exploring is doing: going through a message of {@code at}, downloading {@code what} from it ({@code done} of
     * it, 0 to 1; -1 while unknown), or asking the server for older ones.
     */
    public record Now(Doing doing, Instant at, String what, double done) {
        public Now(Doing doing, Instant at, String what) {
            this(doing, at, what, -1);
        }
    }

    public enum Doing { LOOKING, DOWNLOADING, ASKING_OLDER, STOPPING }

    /** Why exploring a message ended early: the person stopped it, or the disk is full. */
    private static final String STOPPED = "stopped";
    /** Exploring found it saved already (an earlier run): neither saved now nor gone. */
    private static final String THERE = "there";

    private final Map<String, Exploring> exploring = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> stopping = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final List<Runnable> exploringListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Where exploring {@code chatId}'s past is. Any thread. */
    public Exploring exploring(String chatId) {
        Exploring now = exploring.get(chatId);
        if (now != null) return now;
        SavedUpTo.Back b = savedUpTo.back(chatId);
        return new Exploring(false, b == null ? null : b.back(), b != null && b.start(), 0, 0, 0, null);
    }

    /** The chats whose past is being explored now. Any thread. */
    public List<String> exploringChats() {
        return exploring.entrySet().stream().filter(e -> e.getValue().running()).map(Map.Entry::getKey).sorted().toList();
    }

    /** Runs on any thread whenever exploring changes. */
    public void addExploringListener(Runnable r) {
        exploringListeners.add(r);
    }

    public void removeExploringListener(Runnable r) {
        exploringListeners.remove(r);
    }

    /**
     * Goes back through {@code chatId}'s history to its beginning, saving the kinds it has on of what's still there.
     * Page by page, each a job of its own, so what arrives meanwhile isn't kept waiting; it continues where an earlier
     * run stopped and never takes what saving as it arrives already did.
     */
    public void explore(String chatId) {
        if (exploring(chatId).running()) return;
        stopping.remove(chatId);
        Exploring was = exploring(chatId);
        if (was.start()) {
            // It got to the beginning before: again from the newest, saving only what isn't there (kinds turned on since,
            // a file deleted by hand). Pressing it did nothing at all before, and said nothing either.
            savedUpTo.forgetBack(chatId);
            was = new Exploring(false, null, false, 0, 0, 0, null);
        }
        setExploring(chatId, new Exploring(true, was.back(), was.start(), 0, 0, 0, null));
        worker.execute(() -> explorePage(chatId));
    }

    /**
     * Stops at once: what's downloading is cut (a long video, yt-dlp too) and not counted, and the options show
     * "Deteniendo…" until it's over. It used to wait for the file going on to end, saying nothing meanwhile.
     */
    public void stopExploring(String chatId) {
        stopping.add(chatId);
        Exploring e = exploring(chatId);
        if (e.running()) setExploring(chatId, withNow(e, new Now(Doing.STOPPING, null, null)));
        synchronized (stopLock) {
            if (chatId.equals(exploringChat) && exploringThread != null) exploringThread.interrupt();
        }
    }

    private final Object stopLock = new Object();
    /** The thread exploring one message now, and its chat: what stopping interrupts. */
    private Thread exploringThread;
    private String exploringChat;

    private static Exploring withNow(Exploring e, Now now) {
        return new Exploring(true, e.back(), e.start(), e.saved(), e.unavailable(), e.looked(), now);
    }

    private void setExploring(String chatId, Exploring e) {
        exploring.put(chatId, e);
        for (Runnable r : exploringListeners) r.run();
    }

    /** One page: what's loaded and not yet looked at, newest first; then the next older page, or the end. Worker. */
    private void explorePage(String chatId) {
        Wanted wanted = Wanted.of(prefs, chatId);
        Chat chat = chats.chat(chatId);
        if (stopping.remove(chatId) || wanted.none() || chat == null) {
            endExploring(chatId, chat, false);
            return;
        }
        List<Message> old = chats.messages(chatId).stream()
                .filter(m -> !m.deleted() && m.timestamp() != null && !wanted.of(m).isEmpty() && savedUpTo.isOld(chatId, m))
                .sorted(Comparator.comparing(Message::timestamp).reversed()).toList();
        Path dir = folder(chat);
        for (Message m : old) {
            if (stopping.contains(chatId)) break;
            Exploring e = exploring(chatId);
            setExploring(chatId, new Exploring(true, m.timestamp(), e.start(), e.saved(), e.unavailable(), e.looked() + 1,
                    new Now(Doing.LOOKING, m.timestamp(), null)));
            String noSpace = interruptible(chatId, () -> exploreOne(m, wanted.of(m), chatId, dir));
            if (STOPPED.equals(noSpace)) break; // this one wasn't finished: the next run looks at it again
            savedUpTo.exploredBack(chatId, m.timestamp(), false);
            if (noSpace != null) {
                progress.failed(I18n.t("autosave.exploring"), chat, noSpace);
                endExploring(chatId, chat, false);
                return;
            }
        }
        if (stopping.contains(chatId)) {
            stopping.remove(chatId);
            endExploring(chatId, chat, false);
            return;
        }
        doing(chatId, Doing.ASKING_OLDER, exploring(chatId).back(), null);
        chats.loadOlder(chatId).whenComplete((more, err) -> worker.execute(() -> {
            if (err != null || !Boolean.TRUE.equals(more)) endExploring(chatId, chat, err == null);
            else explorePage(chatId);
        }));
    }

    /** Runs {@code work} so that stopping interrupts it; the thread is left clean for the next job either way. */
    private String interruptible(String chatId, java.util.function.Supplier<String> work) {
        synchronized (stopLock) {
            if (stopping.contains(chatId)) return STOPPED;
            exploringThread = Thread.currentThread();
            exploringChat = chatId;
        }
        try {
            return work.get();
        } finally {
            synchronized (stopLock) {
                exploringThread = null;
                exploringChat = null;
                Thread.interrupted(); // an interrupt that came late must not reach saving as it arrives
            }
        }
    }

    /** Saves what {@code m} has of {@code kinds}; null, {@link #STOPPED}, or why there's no room (then exploring stops). */
    private String exploreOne(Message m, Set<SaveKind> kinds, String chatId, Path dir) {
        SaveKind kind = SaveKind.of(m);
        if (kind != null && kinds.contains(kind)) {
            doing(chatId, Doing.DOWNLOADING, m.timestamp(), FileNames.forMessage(m, ZoneId.systemDefault()));
            String why = copyQuietly(m, dir, chatId);
            if (STOPPED.equals(why) || I18n.t("autosave.error.noSpace").equals(why)) return why;
            if (!THERE.equals(why)) count(chatId, why == null);
        }
        if (!kinds.contains(SaveKind.LINKS)) return null;
        for (String url : links(m)) {
            doing(chatId, Doing.DOWNLOADING, m.timestamp(), url);
            String why = linkQuietly(url, m, dir, chatId);
            if (STOPPED.equals(why) || I18n.t("autosave.error.noSpace").equals(why)) return why;
            count(chatId, why == null);
        }
        return null;
    }

    /** Copies {@code m}'s file into {@code dir}: null, {@link #STOPPED}, or why not. */
    private String copyQuietly(Message m, Path dir, String chatId) {
        Path[] file = {null};
        try {
            return copyInto(m, dir, file, done -> arrived(chatId, done)) ? null : THERE;
        } catch (IOException | RuntimeException e) {
            return stopping.contains(chatId) ? STOPPED : why(e, dir, file[0]); // an interrupted copy throws too
        } catch (InterruptedException e) {
            return STOPPED;
        }
    }

    /** Saves the video behind {@code url} into {@code dir}: null, {@link #STOPPED}, or why not. */
    private String linkQuietly(String url, Message m, Path dir, String chatId) {
        try {
            String why = saver.link(url, dir, FileNames.tag(m), done -> arrived(chatId, done));
            return why != null && stopping.contains(chatId) ? STOPPED : why;
        } catch (IOException | RuntimeException e) {
            return stopping.contains(chatId) ? STOPPED : why(e, dir, null);
        } catch (InterruptedException e) {
            return STOPPED;
        }
    }

    private void count(String chatId, boolean saved) {
        setExploring(chatId, exploring(chatId).counted(saved));
    }

    /** How much of the file downloading now has arrived: shown beside its name. */
    private void arrived(String chatId, double done) {
        Exploring e = exploring(chatId);
        Now now = e.now();
        if (stopping.contains(chatId) || now == null || now.doing() != Doing.DOWNLOADING) return;
        setExploring(chatId, withNow(e, new Now(now.doing(), now.at(), now.what(), done)));
    }

    private void doing(String chatId, Doing what, Instant at, String item) {
        if (stopping.contains(chatId)) return; // "Deteniendo…" stays until it's over
        setExploring(chatId, withNow(exploring(chatId), new Now(what, at, item)));
    }

    /** Done (or stopped): one notice for what this run saved, one for what was no longer there. */
    private void endExploring(String chatId, Chat chat, boolean start) {
        Exploring e = exploring(chatId);
        SavedUpTo.Back reached = savedUpTo.back(chatId);
        if (start) savedUpTo.exploredBack(chatId, reached == null ? Instant.EPOCH : reached.back(), true);
        SavedUpTo.Back b = savedUpTo.back(chatId);
        setExploring(chatId, new Exploring(false, b == null ? null : b.back(), b != null && b.start(), e.saved(), e.unavailable(), e.looked(), null));
        if (chat == null) return;
        if (e.saved() > 0) progress.finished(e.saved(), chat);
        if (e.unavailable() > 0) progress.failed(I18n.n("autosave.unavailable", e.unavailable()), chat, I18n.t("autosave.unavailableWhy"));
    }

    /** For tests: runs {@code then} once everything asked so far is done. */
    void afterPending(Runnable then) {
        worker.execute(then);
    }

    private static Saver downloading(MediaSource media) {
        return new Saver() {
            @Override
            public Path media(String mediaUrl, java.util.function.DoubleConsumer progress) throws IOException, InterruptedException {
                try {
                    return media.media(mediaUrl, 0, 0, progress).get();
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IOException(e.getCause());
                }
            }

            @Override
            public String link(String url, Path dir, String tag, java.util.function.DoubleConsumer progress) throws IOException, InterruptedException {
                return LinkSaver.save(url, dir, tag, progress, LinkSaver.tools(), LinkSaver.COOKIES);
            }
        };
    }
}
