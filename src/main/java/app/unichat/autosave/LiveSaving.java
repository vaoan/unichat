package app.unichat.autosave;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.model.SaveKind;
import app.unichat.service.ChatPreferences;
import app.unichat.service.ChatReader;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Saving as messages arrive: each change to a chat saves, in order, what was sent since the last one saved
 * ({@link SavedUpTo}), counting the round for the menu-bar icon and telling what failed. A failure for now stops the
 * chat's round at that message ({@link Holds}). Only the saving thread calls it.
 */
final class LiveSaving {
    private final ChatPreferences prefs;
    private final ChatReader chats;
    private final SavedUpTo savedUpTo;
    private final AutoSave.Saver saver;
    private final AutoSave.Progress progress;
    private final Holds holds;
    /** The round going on. */
    private int total;
    private int done;
    private int savedInRound;
    private Chat lastChat;

    LiveSaving(ChatPreferences prefs, ChatReader chats, SavedUpTo savedUpTo, AutoSave.Saver saver,
            AutoSave.Progress progress, Duration retryAfter, Executor worker) {
        this.prefs = prefs;
        this.chats = chats;
        this.savedUpTo = savedUpTo;
        this.saver = saver;
        this.progress = progress;
        this.holds = new Holds(retryAfter, worker, chatId -> catchUp(chatId, true));
    }

    /**
     * Saves, in order, what was sent in {@code chatId} since the last one saved (others', and mine if chosen).
     * {@code timed}: the try {@link Holds} set off, which counts toward letting a held message go.
     */
    void catchUp(String chatId, boolean timed) {
        Wanted wanted = Wanted.of(prefs, chatId);
        Chat chat = chats.chat(chatId);
        if (wanted.none() || chat == null) return;
        List<Message> fresh = chats.messages(chatId).stream()
                .filter(m -> !m.deleted() && m.timestamp() != null && !wanted.of(m).isEmpty() && savedUpTo.isNew(chatId, m))
                .sorted(Comparator.comparing(Message::timestamp)).toList();
        Path dir = SaveFolders.of(chat);
        for (Message m : fresh) total += work(m, wanted.of(m));
        if (total > done) progress.changed(done, total);
        for (int i = 0; i < fresh.size(); i++) {
            Message m = fresh.get(i);
            int tries = holds.tries(chatId, m.id(), timed && i == 0);
            if (!saveAll(m, wanted.of(m), chat, dir, tries >= AutoSave.MAX_TIMED_TRIES)) {
                // Tried again from here at the chat's next change or by itself: marking it done lost it for good, and
                // saving what came after would move the mark past it all the same.
                holds.hold(chatId, m.id(), tries);
                int left = 0;
                for (Message later : fresh.subList(i + 1, fresh.size())) left += work(later, wanted.of(later));
                total -= left;
                endRoundIfDone();
                return;
            }
            savedUpTo.saved(chatId, m);
            holds.release(chatId);
        }
    }

    /**
     * Saves what {@code m} has of {@code kinds}; false when something failed that may work next time. {@code lastTry}:
     * a failure now is let go as for good ({@link AutoSave#MAX_TIMED_TRIES}).
     */
    private boolean saveAll(Message m, Set<SaveKind> kinds, Chat chat, Path dir, boolean lastTry) {
        boolean ok = true;
        SaveKind kind = SaveKind.of(m); // null: a sticker, or no media
        if (kind != null && kinds.contains(kind)) ok = step(chat, saveMedia(m, chat, dir));
        if (kinds.contains(SaveKind.LINKS)) for (String url : AutoSave.links(m)) ok &= step(chat, saveLink(url, m, chat, dir, false));
        if (!ok && lastTry) {
            System.err.println("Auto-save: gave up on a message of " + chat.id() + " after " + AutoSave.MAX_TIMED_TRIES + " tries");
            return true;
        }
        return ok;
    }

    /**
     * Saves {@code url} of {@code m} into its chat's folder because the person asked: one more in the round going on,
     * and its failure told every time (they're waiting for it), never held. Null when saved; otherwise why not.
     */
    Failure saveAsked(Message m, String url) {
        Chat chat = chats.chat(m.chatId());
        if (chat == null) throw new IllegalArgumentException("No chat " + m.chatId());
        total++;
        progress.changed(done, total);
        Failure f = saveLink(url, m, chat, SaveFolders.of(chat), true);
        step(chat, f);
        return f;
    }

    /** How many things {@code m} gives to save. */
    private static int work(Message m, Set<SaveKind> kinds) {
        SaveKind kind = SaveKind.of(m);
        return (kind != null && kinds.contains(kind) ? 1 : 0) + (kinds.contains(SaveKind.LINKS) ? AutoSave.links(m).size() : 0);
    }

    /** One thing done, saved or not; the round ends when nothing's left. False: it failed, and may work next time. */
    private boolean step(Chat chat, Failure failure) {
        done++;
        if (failure == null) {
            savedInRound++;
            lastChat = chat;
        }
        progress.changed(done, total);
        endRoundIfDone();
        return failure == null || failure.permanent();
    }

    private void endRoundIfDone() {
        if (total == 0 || done < total) return; // nothing going on, or not over yet
        if (savedInRound > 0) progress.finished(savedInRound, lastChat);
        total = done = savedInRound = 0;
        lastChat = null;
        progress.changed(0, 0);
    }

    /** Null when saved (or there already); otherwise why not. */
    private Failure saveMedia(Message m, Chat chat, Path dir) {
        String name = FileNames.forMessage(m, ZoneId.systemDefault()); // what a notice says: the name as sent
        Path[] file = {null};
        try {
            SavedFile.copyInto(saver, m, dir, file, arrived -> { });
            return null;
        } catch (IOException | RuntimeException e) {
            return failed(name, chat, m, new Failure(AutoSave.why(e, dir, file[0]), e instanceof AutoSave.Gone), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Failure.forNow(e.toString());
        }
    }

    /** Null when saved; otherwise why not. {@code asked}: the person asked for it, so a failure is told every time. */
    private Failure saveLink(String url, Message m, Chat chat, Path dir, boolean asked) {
        try {
            Failure f = saver.link(url, dir, FileNames.tag(m), arrived -> { });
            return f == null ? null : failed(url, chat, m, f, asked);
        } catch (IOException | RuntimeException e) {
            return failed(url, chat, m, Failure.forNow(AutoSave.why(e, dir, null)), asked);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Failure.forNow(e.toString());
        }
    }

    /** Tells and logs it (a failure for now only the first time, unless {@code asked}); gives it back. */
    private Failure failed(String what, Chat chat, Message m, Failure f, boolean asked) {
        if (!asked && !f.permanent() && !holds.firstTime(chat.id(), what, f.why())) return f;
        System.err.println("Auto-save: " + what + " from " + chat.id() + " not saved" + (f.permanent() ? "" : " (tried again later)")
                + ": " + f.why());
        progress.failed(what, chat, m.id(), f.why());
        return f;
    }
}
