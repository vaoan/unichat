package app.unichat.ui.desktop;

/**
 * What a notification's click opens: a chat, or one message in it ({@code <chat id>#<message id>}). A notice that a
 * file couldn't be saved led nowhere: its click now goes to that message. {@code #} is in no Matrix room or event id,
 * and the helper (`native/UnichatBar.swift`) keeps the text as it is.
 */
public final class NotificationTarget {
    private NotificationTarget() { }

    public static String of(String chatId, String messageId) {
        return messageId == null ? chatId : chatId + "#" + messageId;
    }

    public static String chat(String target) {
        int at = target.indexOf('#');
        return at < 0 ? target : target.substring(0, at);
    }

    /** The message to go to, or null for the chat alone. */
    public static String message(String target) {
        int at = target.indexOf('#');
        return at < 0 ? null : target.substring(at + 1);
    }
}
