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
import app.unichat.support.Urls;
import app.unichat.video.YtDlp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
 *
 * <p>The facade others use: saving as messages arrive is {@link LiveSaving} (its failures for now held by
 * {@link Holds}), exploring the past {@link PastExplorer}, a file written {@link SavedFile}, a folder moved
 * {@link SavedMove}.
 */
public final class AutoSave implements ChatService.Listener {
    /** What saving does, apart so tests don't need a server or yt-dlp. */
    interface Saver {
        /**
         * The file behind {@code mediaUrl}, downloaded; {@code progress}: how much has arrived, 0 to 1, when that's
         * known. {@link Gone} when it never will be.
         */
        Path media(String mediaUrl, java.util.function.DoubleConsumer progress) throws IOException, InterruptedException;

        /**
         * The video behind {@code url} saved into {@code dir}: null, or why not. {@code tag}: the message's id, which
         * goes in the name ({@link FileNames#tag}).
         */
        Failure link(String url, Path dir, String tag, java.util.function.DoubleConsumer progress) throws IOException, InterruptedException;
    }

    /** A file that can never be downloaded: deleted, expired, not on the server any more. */
    static final class Gone extends IOException {
        Gone(String why, Throwable cause) {
            super(why, cause);
        }
    }

    /** What saving is doing, for the menu-bar icon and the notices. Called on the saving thread. */
    public interface Progress {
        /** {@code done} of {@code total} in the round going on; total 0: nothing going on. */
        void changed(int done, int total);

        /** A round ended with {@code saved} files saved; {@code chat} is where the last came from. */
        void finished(int saved, Chat chat);

        /**
         * {@code what} (a file's name, a link) from {@code chat} couldn't be saved, because {@code why}; {@code messageId}
         * is the message it came in (null when it's no one message: exploring stopped, files gone), for the notice to go to.
         */
        void failed(String what, Chat chat, String messageId, String why);
    }

    private static final Map<ChatSession, AutoSave> BY_SERVICE = new WeakHashMap<>();

    private final ChatPreferences prefs;
    private final ChatReader chats;
    private final SavedUpTo savedUpTo;
    private volatile Progress progress = new Progress() {
        @Override public void changed(int done, int total) { }
        @Override public void finished(int saved, Chat chat) { }
        @Override public void failed(String what, Chat chat, String messageId, String why) { }
    };
    /** What {@link #progress} is now: the parts keep this one, {@link #setProgress} may change it later. */
    private final Progress tell = new Progress() {
        @Override public void changed(int done, int total) { progress.changed(done, total); }
        @Override public void finished(int saved, Chat chat) { progress.finished(saved, chat); }
        @Override public void failed(String what, Chat chat, String messageId, String why) { progress.failed(what, chat, messageId, why); }
    };
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "autosave");
        t.setDaemon(true);
        return t;
    });

    /**
     * How often a chat held by a failure for now (no space, the network, an error yt-dlp's words don't place) tries again
     * by itself, so a quiet chat isn't left waiting for its next message: often enough that a disk freed or a network
     * back is noticed within the half hour, rarely enough that a failing download isn't repeated all day.
     */
    static final java.time.Duration RETRY_AFTER = java.time.Duration.ofMinutes(30);
    /**
     * After this many of those tries it's let go, for good, so what came after is saved: with {@link #RETRY_AFTER},
     * a day, which covers a night offline or a full disk until the person (told at the first failure) frees it. The
     * tries the chat's own changes make don't count, so a busy group doesn't give up within minutes.
     */
    static final int MAX_TIMED_TRIES = 48;
    private final LiveSaving live;
    private final PastExplorer past;

    AutoSave(ChatPreferences prefs, ChatReader chats, SavedUpTo savedUpTo, java.time.Duration retryAfter, Saver saver) {
        this.prefs = prefs;
        this.chats = chats;
        this.savedUpTo = savedUpTo;
        this.live = new LiveSaving(prefs, chats, savedUpTo, saver, tell, retryAfter, worker);
        this.past = new PastExplorer(prefs, chats, savedUpTo, saver, tell, worker);
    }

    /** The one for {@code svc}, listening from the first call: it's started with the service, before its first sync. */
    public static synchronized AutoSave of(ChatService svc) {
        return BY_SERVICE.computeIfAbsent(svc, s -> {
            AutoSave a = new AutoSave(svc, svc, new SavedUpTo(Settings.dataDir().resolve("autosave.json")),
                    RETRY_AFTER, downloading(svc));
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
        return onWorker(() -> SavedMove.toBase(chats.chats(), folder));
    }

    /** A chat's own folder (null: under the base again), taking along what it saved where it was. */
    public java.util.concurrent.CompletableFuture<Integer> setOwn(Chat chat, Path folder) {
        return onWorker(() -> SavedMove.toOwn(chat, folder));
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
        worker.execute(() -> live.catchUp(chatId, false));
    }

    /** The links in what it says, and the page a reel shown as a picture points to. */
    static Set<String> links(Message m) {
        Set<String> out = new LinkedHashSet<>(Urls.in(m.body()));
        // A video that came with its page (Instagram's reels) is that page's video: downloading the page again saved
        // the same reel twice, the second one in VP9, which a Mac doesn't open.
        if (m.externalUrl() != null && m.externalUrl().startsWith("http") && m.type() != app.unichat.model.MessageType.VIDEO) out.add(m.externalUrl());
        return out;
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

    /** Where exploring {@code chatId}'s past is. Any thread. */
    public Exploring exploring(String chatId) {
        return past.exploring(chatId);
    }

    /** The chats whose past is being explored now. Any thread. */
    public List<String> exploringChats() {
        return past.exploringChats();
    }

    /** Runs on any thread whenever exploring changes. */
    public void addExploringListener(Runnable r) {
        past.addListener(r);
    }

    public void removeExploringListener(Runnable r) {
        past.removeListener(r);
    }

    /**
     * Goes back through {@code chatId}'s history to its beginning, saving the kinds it has on of what's still there.
     * Page by page, each a job of its own, so what arrives meanwhile isn't kept waiting; it continues where an earlier
     * run stopped and never takes what saving as it arrives already did.
     */
    public void explore(String chatId) {
        past.explore(chatId);
    }

    /**
     * Stops at once: what's downloading is cut (a long video, yt-dlp too) and not counted, and the options show
     * "Deteniendo…" until it's over. It used to wait for the file going on to end, saying nothing meanwhile.
     */
    public void stopExploring(String chatId) {
        past.stop(chatId);
    }

    /** For tests: runs {@code then} once everything asked so far is done. */
    void afterPending(Runnable then) {
        worker.execute(then);
    }

    static Saver downloading(MediaSource media) {
        return new Saver() {
            @Override
            public Path media(String mediaUrl, java.util.function.DoubleConsumer progress) throws IOException, InterruptedException {
                try {
                    return media.media(mediaUrl, 0, 0, progress).get();
                } catch (java.util.concurrent.ExecutionException e) {
                    if (media.gone(e.getCause())) throw new Gone(e.getCause().getMessage(), e.getCause());
                    throw new IOException(e.getCause());
                }
            }

            @Override
            public Failure link(String url, Path dir, String tag, java.util.function.DoubleConsumer progress) throws IOException, InterruptedException {
                return LinkSaver.save(url, dir, tag, progress, LinkSaver.tools(), LinkSaver.COOKIES);
            }
        };
    }
}
