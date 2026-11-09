package app.unichat.service;

import java.util.List;

/**
 * One of the person's folders of chats; {@code chatKeys} as in {@link ChatKeys}. A {@code favorite} one shows among the
 * favorite chats.
 */
public record Folder(String id, String name, List<String> chatKeys, boolean favorite) {
    public Folder(String id, String name, List<String> chatKeys) {
        this(id, name, chatKeys, false);
    }
}
