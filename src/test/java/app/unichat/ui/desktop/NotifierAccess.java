package app.unichat.ui.desktop;

import org.jspecify.annotations.Nullable;

/** For tests outside the package: the chat of the last notification or reminder {@code n} posted, null when none. */
public final class NotifierAccess {
    private NotifierAccess() {}

    public static @Nullable String lastChatId(Notifier n) {
        return n.lastChatId();
    }
}
