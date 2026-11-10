package app.unichat.autosave;

import app.unichat.autosave.AutoSave.Doing;
import app.unichat.autosave.AutoSave.Exploring;
import app.unichat.autosave.AutoSave.Now;
import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.model.SaveKind;
import app.unichat.service.ChatPreferences;
import app.unichat.service.ChatReader;
import app.unichat.support.I18n;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Exploring a chat's past ({@link AutoSave#explore}): page by page back to its beginning, each page a job of its own on
 * the saving thread so what arrives isn't kept waiting, continuing where an earlier run stopped
 * ({@link SavedUpTo.Back}); says what it's doing as it goes ({@link Exploring}) and stops at once when asked.
 */
final class PastExplorer {
    /** Why exploring a message ended early: the person stopped it, or the disk is full. */
    private static final String STOPPED = "stopped";
    /** Exploring found it saved already (an earlier run): neither saved now nor gone. */
    private static final String THERE = "there";

    private final ChatPreferences prefs;
    private final ChatReader chats;
    private final SavedUpTo savedUpTo;
    private final AutoSave.Saver saver;
    private final AutoSave.Progress progress;
    private final Executor worker;
    private final Map<String, Exploring> exploring = new ConcurrentHashMap<>();
    private final Set<String> stopping = ConcurrentHashMap.newKeySet();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Object stopLock = new Object();
    /** The thread exploring one message now, and its chat: what stopping interrupts. */
    private Thread exploringThread;
    private String exploringChat;

    PastExplorer(ChatPreferences prefs, ChatReader chats, SavedUpTo savedUpTo, AutoSave.Saver saver,
            AutoSave.Progress progress, Executor worker) {
        this.prefs = prefs;
        this.chats = chats;
        this.savedUpTo = savedUpTo;
        this.saver = saver;
        this.progress = progress;
        this.worker = worker;
    }

    /** Where exploring {@code chatId}'s past is. Any thread. */
    Exploring exploring(String chatId) {
        Exploring now = exploring.get(chatId);
        if (now != null) return now;
        SavedUpTo.Back b = savedUpTo.back(chatId);
        return new Exploring(false, b == null ? null : b.back(), b != null && b.start(), 0, 0, 0, null);
    }

    /** The chats whose past is being explored now. Any thread. */
    List<String> exploringChats() {
        return exploring.entrySet().stream().filter(e -> e.getValue().running()).map(Map.Entry::getKey).sorted().toList();
    }

    void addListener(Runnable r) {
        listeners.add(r);
    }

    void removeListener(Runnable r) {
        listeners.remove(r);
    }

    /** Starts exploring {@code chatId}, unless it's going on already. */
    void explore(String chatId) {
        if (exploring(chatId).running()) return;
        stopping.remove(chatId);
        Exploring was = exploring(chatId);
        if (was.start()) {
            // It got to the beginning before: again from the newest, saving only what isn't there (kinds turned on since,
            // a file deleted by hand). Pressing it did nothing at all before, and said nothing either.
            savedUpTo.forgetBack(chatId);
            was = new Exploring(false, null, false, 0, 0, 0, null);
        }
        set(chatId, new Exploring(true, was.back(), was.start(), 0, 0, 0, null));
        worker.execute(() -> page(chatId));
    }

    /** Stops at once: what's downloading is cut and not counted; "Deteniendo…" until it's over. */
    void stop(String chatId) {
        stopping.add(chatId);
        Exploring e = exploring(chatId);
        if (e.running()) set(chatId, withNow(e, new Now(Doing.STOPPING, null, null)));
        synchronized (stopLock) {
            if (chatId.equals(exploringChat) && exploringThread != null) exploringThread.interrupt();
        }
    }

    private static Exploring withNow(Exploring e, Now now) {
        return new Exploring(true, e.back(), e.start(), e.saved(), e.unavailable(), e.looked(), now);
    }

    private void set(String chatId, Exploring e) {
        exploring.put(chatId, e);
        for (Runnable r : listeners) r.run();
    }

    /** One page: what's loaded and not yet looked at, newest first; then the next older page, or the end. Worker. */
    private void page(String chatId) {
        Wanted wanted = Wanted.of(prefs, chatId);
        Chat chat = chats.chat(chatId);
        if (stopping.remove(chatId) || wanted.none() || chat == null) {
            end(chatId, chat, false);
            return;
        }
        List<Message> old = chats.messages(chatId).stream()
                .filter(m -> !m.deleted() && m.timestamp() != null && !wanted.of(m).isEmpty() && savedUpTo.isOld(chatId, m))
                .sorted(Comparator.comparing(Message::timestamp).reversed()).toList();
        Path dir = SaveFolders.of(chat);
        for (Message m : old) {
            if (stopping.contains(chatId)) break;
            Exploring e = exploring(chatId);
            set(chatId, new Exploring(true, m.timestamp(), e.start(), e.saved(), e.unavailable(), e.looked() + 1,
                    new Now(Doing.LOOKING, m.timestamp(), null)));
            String noSpace = interruptible(chatId, () -> exploreOne(m, wanted.of(m), chatId, dir));
            if (STOPPED.equals(noSpace)) break; // this one wasn't finished: the next run looks at it again
            savedUpTo.exploredBack(chatId, m.timestamp(), false);
            if (noSpace != null) {
                progress.failed(I18n.t("autosave.exploring"), chat, noSpace);
                end(chatId, chat, false);
                return;
            }
        }
        if (stopping.contains(chatId)) {
            stopping.remove(chatId);
            end(chatId, chat, false);
            return;
        }
        doing(chatId, Doing.ASKING_OLDER, exploring(chatId).back(), null);
        chats.loadOlder(chatId).whenComplete((more, err) -> worker.execute(() -> {
            if (err != null || !Boolean.TRUE.equals(more)) end(chatId, chat, err == null);
            else page(chatId);
        }));
    }

    /** Runs {@code work} so that stopping interrupts it; the thread is left clean for the next job either way. */
    private String interruptible(String chatId, Supplier<String> work) {
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
        for (String url : AutoSave.links(m)) {
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
            return SavedFile.copyInto(saver, m, dir, file, done -> arrived(chatId, done)) ? null : THERE;
        } catch (IOException | RuntimeException e) {
            return stopping.contains(chatId) ? STOPPED : AutoSave.why(e, dir, file[0]); // an interrupted copy throws too
        } catch (InterruptedException e) {
            return STOPPED;
        }
    }

    /** Saves the video behind {@code url} into {@code dir}: null, {@link #STOPPED}, or why not. */
    private String linkQuietly(String url, Message m, Path dir, String chatId) {
        try {
            Failure f = saver.link(url, dir, FileNames.tag(m), done -> arrived(chatId, done));
            String why = f == null ? null : f.why();
            return why != null && stopping.contains(chatId) ? STOPPED : why;
        } catch (IOException | RuntimeException e) {
            return stopping.contains(chatId) ? STOPPED : AutoSave.why(e, dir, null);
        } catch (InterruptedException e) {
            return STOPPED;
        }
    }

    private void count(String chatId, boolean saved) {
        set(chatId, exploring(chatId).counted(saved));
    }

    /** How much of the file downloading now has arrived: shown beside its name. */
    private void arrived(String chatId, double done) {
        Exploring e = exploring(chatId);
        Now now = e.now();
        if (stopping.contains(chatId) || now == null || now.doing() != Doing.DOWNLOADING) return;
        set(chatId, withNow(e, new Now(now.doing(), now.at(), now.what(), done)));
    }

    private void doing(String chatId, Doing what, Instant at, String item) {
        if (stopping.contains(chatId)) return; // "Deteniendo…" stays until it's over
        set(chatId, withNow(exploring(chatId), new Now(what, at, item)));
    }

    /** Done (or stopped): one notice for what this run saved, one for what was no longer there. */
    private void end(String chatId, Chat chat, boolean start) {
        Exploring e = exploring(chatId);
        SavedUpTo.Back reached = savedUpTo.back(chatId);
        if (start) savedUpTo.exploredBack(chatId, reached == null ? Instant.EPOCH : reached.back(), true);
        SavedUpTo.Back b = savedUpTo.back(chatId);
        set(chatId, new Exploring(false, b == null ? null : b.back(), b != null && b.start(), e.saved(), e.unavailable(), e.looked(), null));
        if (chat == null) return;
        if (e.saved() > 0) progress.finished(e.saved(), chat);
        if (e.unavailable() > 0) progress.failed(I18n.n("autosave.unavailable", e.unavailable()), chat, I18n.t("autosave.unavailableWhy"));
    }
}
