package app.unichat.autosave;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.model.SaveKind;
import app.unichat.service.ChatPreferences;
import app.unichat.service.ChatReader;
import app.unichat.support.I18n;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Keeping what chats say ({@link SaveKind#TEXTS}), to read later what was deleted. What a chat says is taken the moment
 * it changes, on the caller's thread, and written on a thread of its own: behind a video downloading, a message deleted a
 * minute later would be gone before its turn. The chat's readable copy ({@link TextCopy}) is made again shortly after
 * anything new, this computer's or another's ({@link #arrived}).
 */
final class TextKeeping {
    /**
     * How long looking at a deleted message waits for the cloud to bring what the person's other computers kept: enough
     * for a round of small files, short enough that without a network the copy here is shown soon.
     */
    static final Duration FETCH_WAIT = Duration.ofSeconds(8);

    private final ChatPreferences prefs;
    private final ChatReader chats;
    private final TextLog log;
    private final Duration settle;
    private final Duration fetchWait;
    private final AutoSave.Progress progress;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "autosave-texts");
        t.setDaemon(true);
        return t;
    });
    /** Chats whose readable copy is due to be made again. */
    private final Set<String> copyDue = ConcurrentHashMap.newKeySet();
    /** What was kept is due to go up. */
    private final java.util.concurrent.atomic.AtomicBoolean sendDue = new java.util.concurrent.atomic.AtomicBoolean();
    /** Chats whose copy failed and was told: told again only after it worked. Worker only. */
    private final Set<String> failing = new HashSet<>();
    private volatile Runnable written = () -> { };
    private volatile Supplier<CompletableFuture<?>> fetch = () -> CompletableFuture.completedFuture(null);

    /**
     * @param settle how long after a change the readable copy is made again and what was kept goes up: a busy chat's
     *               messages make one copy and one upload of its month's file, not one each
     * @param fetchWait {@link #FETCH_WAIT} (tests pass zero)
     */
    TextKeeping(ChatPreferences prefs, ChatReader chats, TextLog log, Duration settle, Duration fetchWait, AutoSave.Progress progress) {
        this.fetchWait = fetchWait;
        this.prefs = prefs;
        this.chats = chats;
        this.log = log;
        this.settle = settle;
        this.progress = progress;
    }

    /**
     * {@code written}: runs after this computer wrote something (so it goes up now); {@code fetch}: brings what the other
     * computers kept, asked before looking at a message.
     */
    void setSync(Runnable written, Supplier<CompletableFuture<?>> fetch) {
        this.written = written;
        this.fetch = fetch;
    }

    /** {@code chatId}'s messages changed: what they say now is taken here and now, and written on the texts thread. */
    void changed(String chatId) {
        Wanted wanted = Wanted.of(prefs, chatId);
        if (!wanted.texts()) return;
        Instant now = Instant.now();
        List<SavedText> said = chats.messages(chatId).stream().filter(m -> wanted.of(m).contains(SaveKind.TEXTS))
                .map(m -> SavedText.of(m, now)).filter(Objects::nonNull).toList();
        if (said.isEmpty()) return;
        worker.execute(() -> keep(chatId, said));
    }

    /** The other computers' files of these chats (their keys) changed: their readable copies are made again. */
    void arrived(Set<String> keys) {
        for (Chat c : chats.chats()) if (keys.contains(TextLog.key(c.id())) && Wanted.of(prefs, c.id()).texts()) copySoon(c.id());
    }

    /**
     * Every version of {@code m} kept by any of the person's computers, oldest first, after asking the cloud for what's
     * new (at most {@code fetchWait}, {@link #FETCH_WAIT}; without it, what's here); empty: there's no copy.
     */
    CompletableFuture<List<SavedText>> said(Message m) {
        CompletableFuture<?> fresh;
        try {
            fresh = fetch.get();
        } catch (RuntimeException e) {
            fresh = CompletableFuture.completedFuture(null);
        }
        return fresh.handle((r, e) -> null).completeOnTimeout(null, fetchWait.toMillis(), TimeUnit.MILLISECONDS)
                .thenApplyAsync(x -> {
                    try {
                        return log.versions(m.chatId(), m.id());
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }, worker);
    }

    /**
     * For tests (a zero {@code settle}): runs {@code then} once everything asked so far is done, and what that set off (a
     * copy or an upload due now): the second turn comes after what the first one's jobs scheduled.
     */
    void afterPending(Runnable then) {
        worker.execute(() -> worker.execute(then));
    }

    private void keep(String chatId, List<SavedText> said) {
        try {
            if (!log.record(chatId, said)) return;
            failing.remove(chatId);
            copySoon(chatId);
            if (sendDue.compareAndSet(false, true)) worker.schedule(() -> {
                sendDue.set(false);
                written.run();
            }, settle.toMillis(), TimeUnit.MILLISECONDS);
        } catch (IOException e) {
            tellFailed(chatId, e, AutoSave.textsDir());
        }
    }

    private void copySoon(String chatId) {
        if (copyDue.add(chatId)) worker.schedule(() -> copy(chatId), settle.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void copy(String chatId) {
        copyDue.remove(chatId);
        Chat chat = chats.chat(chatId);
        if (chat == null) return;
        try {
            TextCopy.write(chat, log.merged(chatId));
            failing.remove(chatId);
        } catch (IOException e) {
            tellFailed(chatId, e, SaveFolders.of(chat));
        }
    }

    /**
     * Logged each time, told once until it works again: a full disk would tell it at every message. {@code where}: the
     * folder it was writing to, whose space and permission say why.
     */
    private void tellFailed(String chatId, IOException e, java.nio.file.Path where) {
        Chat chat = chats.chat(chatId);
        System.err.println("Auto-save: the texts of " + chatId + " not kept: " + e);
        if (chat == null || !failing.add(chatId)) return;
        progress.failed(I18n.t("autosave.texts.what"), chat, null, AutoSave.why(e, where, null));
    }
}
