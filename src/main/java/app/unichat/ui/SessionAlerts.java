package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import app.unichat.ui.chats.ChatFilter;
import app.unichat.ui.desktop.NotificationPolicy;
import app.unichat.ui.desktop.Notifier;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

/**
 * What the session tells the person with the window open or not: each message's notification (and its voice), a
 * reminder come due, and the unread count on the menu bar and the window's title. The window's view may be let go
 * while it's closed ({@link AwayRelease}), so none of this lives in it: it lasts as long as the session.
 */
public final class SessionAlerts {
    /** A busy account changes chats many times a second: the count follows at most every so often. */
    private static final long COUNT_GAP_MS = 250;

    private final ChatService svc;
    private final Stage stage;
    private final Notifier notifier;
    private final Supplier<@Nullable String> openChat;
    private final Throttle counting = new Throttle(COUNT_GAP_MS);

    private final ChatService.Listener listener = new ChatService.Listener() {
        @Override public void chatsChanged() {
            counting.schedule(counting, () -> {
                counting.ran();
                showUnread();
            });
        }
        @Override public void incomingMessage(Chat chat, Message m) { Platform.runLater(() -> incoming(chat, m)); }
        @Override public void reminderDue(Chat chat) { Platform.runLater(() -> reminder(chat)); }
    };

    /** {@code openChat}: the chat open in the window, null when none or the window was let go. */
    public SessionAlerts(ChatService svc, Stage stage, Notifier notifier, Supplier<@Nullable String> openChat) {
        this.svc = svc;
        this.stage = stage;
        this.notifier = notifier;
        this.openChat = openChat;
    }

    /** From now on, starting with the count as it is. FX thread. */
    public void start() {
        svc.addListener(listener);
        showUnread();
    }

    /** The session ended (logging out). */
    public void stop() {
        svc.removeListener(listener);
    }

    private void showUnread() {
        long unread = svc.chats().stream().filter(ChatFilter::inboxUnread).count();
        notifier.setUnread((int) unread);
        stage.setTitle(unread > 0 ? "Unichat (" + unread + ")" : "Unichat");
    }

    private void incoming(Chat chat, Message m) {
        boolean viewing = stage.isFocused() && stage.isShowing() && chat.id().equals(openChat.get());
        var settings = Settings.get();
        if (NotificationPolicy.shouldNotify(chat.archived(), settings.notifyArchived(), viewing)) notifier.notify(chat, m);
        if (settings.speakMessages() && (!chat.archived() || settings.notifyArchived())) Speaker.say(A11yText.spoken(chat, m, settings.notificationPreview()));
    }

    private void reminder(Chat chat) {
        notifier.reminder(chat);
        if (Settings.get().speakMessages()) Speaker.say(I18n.t("main.reminderSpoken", chat.title()));
    }
}
