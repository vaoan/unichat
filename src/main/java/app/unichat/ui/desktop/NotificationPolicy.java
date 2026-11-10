package app.unichat.ui.desktop;

/** Whether an incoming message (already filtered for muted chats) produces a desktop notification. */
public final class NotificationPolicy {
    private NotificationPolicy() {}

    /**
     * @param archived       the chat is archived in Unichat
     * @param notifyArchived Ajustes → Notificaciones → "Chats archivados"
     * @param viewing        the user is reading that chat in a focused window
     */
    public static boolean shouldNotify(boolean archived, boolean notifyArchived, boolean viewing) {
        if (viewing) return false;
        return !archived || notifyArchived;
    }
}
