package app.unichat.ui.timeline;

import app.unichat.service.ChatService;
import org.jspecify.annotations.Nullable;

/** For tests outside the package: a text waiting in {@code svc}'s queue whose correction never comes back. */
public final class SendQueueAccess {
    private SendQueueAccess() {}

    public static void submitNeverAnswered(ChatService svc, String chatId, String text) {
        SendQueue q = SendQueue.of(svc);
        q.useWriter(() -> new AiReplyButton.Writer() {
            private final java.util.concurrent.CountDownLatch stopped = new java.util.concurrent.CountDownLatch(1);

            @Override public @Nullable String write(String prompt) {
                try {
                    stopped.await(); // until it's stopped
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }

            @Override public void cancel() { stopped.countDown(); }
        });
        q.submit(chatId, text, null);
    }
}
