package app.unichat.matrix;

import app.unichat.model.Chat;
import app.unichat.service.ChatService.Listener;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static app.unichat.matrix.BridgeProtocol.*;
import static app.unichat.matrix.MatrixClient.CLIENT;
import static app.unichat.support.Urls.enc;
import static app.unichat.matrix.MatrixLogin.userMessage;

/** What every part of the Matrix service shares: the connection, the rooms, the background threads and the listeners. */
final class MatrixCore {
    final MatrixClient client;
    final SyncProcessor proc;
    final String userId;
    final String serverName;
    final String accessToken;
    private final AtomicLong txnCounter = new AtomicLong();
    volatile boolean running;
    volatile boolean initialSyncDone;
    /**
     * Background work (sends, downloads, bot commands). Platform threads, not virtual ones: almost everything here
     * takes {@code synchronized (proc)} or a bot lock, and on Java 21 a virtual thread blocked on a monitor pins its
     * carrier. With as many waiters as CPU cores, nothing else could run: a bot command waiting for its own send
     * froze all background work.
     */
    final ExecutorService io = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "matrix-io");
        t.setDaemon(true);
        return t;
    });
    final List<Listener> listeners = new CopyOnWriteArrayList<>();

    MatrixCore(MatrixClient client, SyncProcessor proc, String userId, String serverName, String accessToken) {
        this.client = client;
        this.proc = proc;
        this.userId = userId;
        this.serverName = serverName;
        this.accessToken = accessToken;
    }

    String newTxnId() {
        return "uc" + System.currentTimeMillis() + "." + txnCounter.incrementAndGet();
    }

    Chat chat(String chatId) {
        synchronized (proc) {
            MatrixRoom r = proc.room(chatId);
            return r == null ? null : r.chat;
        }
    }

    void fire(java.util.function.Consumer<Listener> call) {
        for (Listener l : listeners) {
            try {
                call.accept(l);
            } catch (RuntimeException e) {
                System.err.println("Listener failed: " + e);
            }
        }
    }

    interface IoAction { void run() throws Exception; }

    CompletableFuture<Void> run(IoAction action) {
        return CompletableFuture.runAsync(() -> {
            try {
                action.run();
            } catch (CompletionException e) {
                throw e;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, io);
    }

    <T> CompletableFuture<T> call(java.util.concurrent.Callable<T> call) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return call.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, io);
    }
}
