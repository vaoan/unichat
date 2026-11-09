package app.unichat.service;

import app.unichat.model.Chat;
import app.unichat.model.ConnectionState;
import app.unichat.model.Message;

import java.util.function.BooleanSupplier;

/**
 * Waits for a condition, checking it again each time something says it {@link #changed}: no polling, no sleeps.
 * The condition runs outside the lock, so it may take the service's own locks without deadlocking a listener.
 */
public final class Changes {
    private final Object lock = new Object();
    private long version;

    public void changed() {
        synchronized (lock) {
            version++;
            lock.notifyAll();
        }
    }

    /** One that hears every event of {@code svc}. */
    public static Changes of(ChatSession svc) {
        Changes c = new Changes();
        svc.addListener(c.listener());
        return c;
    }

    /** {@link #until(BooleanSupplier)}, saying what never happened when the JUnit timeout stops it. */
    public void until(String what, BooleanSupplier done) throws InterruptedException {
        try {
            until(done);
        } catch (InterruptedException timedOut) {
            throw new AssertionError("never happened: " + what, timedOut);
        }
    }

    /** Blocks until {@code done} holds; the tests' JUnit timeout is the only limit. */
    public void until(BooleanSupplier done) throws InterruptedException {
        while (true) {
            long seen;
            synchronized (lock) {
                seen = version;
            }
            if (done.getAsBoolean()) return;
            synchronized (lock) {
                while (version == seen) lock.wait();
            }
        }
    }

    /** A listener that reports every event of a {@link ChatService}. */
    public ChatService.Listener listener() {
        return new ChatService.Listener() {
            @Override public void chatsChanged() { changed(); }
            @Override public void messagesChanged(String chatId) { changed(); }
            @Override public void accountsChanged() { changed(); }
            @Override public void connectionChanged(ConnectionState state) { changed(); }
            @Override public void incomingMessage(Chat chat, Message message) { changed(); }
            @Override public void reminderDue(Chat chat) { changed(); }
        };
    }
}
