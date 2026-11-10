package app.unichat.autosave;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The chats a failure for now holds (no space, the network, an error yt-dlp's words don't place): the message holding
 * each, how many timed tries it failed, the one try by itself after {@link AutoSave#RETRY_AFTER}, and what was told
 * already, so the same failure is told once until the chat gets past it. Only the saving thread touches it.
 */
final class Holds {
    /** A message holding a chat, and how many timed tries it failed. */
    private record Held(String messageId, int timedTries) {}

    private final Map<String, Held> held = new HashMap<>();
    private final Set<String> retrying = new HashSet<>();
    private final Map<String, Set<String>> toldForNow = new HashMap<>();
    private final Duration retryAfter;
    private final Executor worker;
    /** A timed try of a held chat. */
    private final Consumer<String> retry;

    Holds(Duration retryAfter, Executor worker, Consumer<String> retry) {
        this.retryAfter = retryAfter;
        this.worker = worker;
        this.retry = retry;
    }

    /**
     * The timed tries {@code messageId} has failed in {@code chatId}, counting this one when it's {@code timed}: only
     * the timer's tries count, so a busy group doesn't give up within minutes.
     */
    int tries(String chatId, String messageId, boolean timed) {
        Held h = held.get(chatId);
        return (h != null && h.messageId().equals(messageId) ? h.timedTries() : 0) + (timed ? 1 : 0);
    }

    /** {@code messageId} holds {@code chatId} after {@code tries} timed tries; it's tried again by itself later. */
    void hold(String chatId, String messageId, int tries) {
        held.put(chatId, new Held(messageId, tries));
        retryLater(chatId);
    }

    /** The chat got past what held it: a new failure is told again. */
    void release(String chatId) {
        toldForNow.remove(chatId);
        held.remove(chatId);
    }

    /** True the first time {@code what} fails for now with {@code why} in {@code chatId} since it was released. */
    boolean firstTime(String chatId, String what, String why) {
        return toldForNow.computeIfAbsent(chatId, k -> new HashSet<>()).add(what + "\n" + why);
    }

    /** One try of a held chat after {@link #retryAfter}, however many failures asked for it meanwhile. */
    private void retryLater(String chatId) {
        if (!retrying.add(chatId)) return;
        CompletableFuture.delayedExecutor(retryAfter.toSeconds(), TimeUnit.SECONDS, worker).execute(() -> {
            retrying.remove(chatId);
            retry.accept(chatId);
        });
    }
}
