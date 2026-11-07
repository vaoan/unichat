package app.unichat.service;

import java.util.List;

/** One of the person's folders of chats; {@code chatKeys} as in {@link ChatKeys}. */
public record Folder(String id, String name, List<String> chatKeys) {}
