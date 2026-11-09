package app.unichat.matrix;

import app.unichat.model.Chat;
import app.unichat.model.ConnectionState;
import app.unichat.model.Message;
import app.unichat.service.ChatService;
import app.unichat.service.ChatSession;

import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;

/**
 * Waits for a service to reach a state by reacting to its listener: the condition is checked now and again each time
 * the service says something changed, never on a timer. JUnit's default timeout is the net for a change that never comes.
 */
final class ServiceChanges implements ChatService.Listener {
    private final Semaphore changed = new Semaphore(0);

    private ServiceChanges() {}

    static ServiceChanges of(ChatSession svc) {
        ServiceChanges c = new ServiceChanges();
        svc.addListener(c);
        return c;
    }

    void until(String what, BooleanSupplier condition) throws InterruptedException {
        try {
            while (!condition.getAsBoolean()) {
                changed.acquire();
                changed.drainPermits(); // one check covers every change so far
            }
        } catch (InterruptedException timedOut) {
            throw new AssertionError("never happened: " + what, timedOut);
        }
    }

    private void poke() { changed.release(); }

    @Override public void chatsChanged() { poke(); }
    @Override public void messagesChanged(String chatId) { poke(); }
    @Override public void accountsChanged() { poke(); }
    @Override public void connectionChanged(ConnectionState state) { poke(); }
    @Override public void incomingMessage(Chat chat, Message message) { poke(); }
}
