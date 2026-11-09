package app.unichat.service;

import app.unichat.model.Chat;

import java.util.List;
import java.util.function.Consumer;

/** The person's own folders of chats. They live in Unichat's preferences file, not on the server. */
public interface ChatFolders {
    List<Folder> folders();
    String createFolder(String name);
    void renameFolder(String id, String name);
    void deleteFolder(String id);
    /** Shows {@code id} among the favorites, or not. */
    void setFolderFavorite(String id, boolean favorite);
    void setInFolder(String folderId, Chat chat, boolean in);
    /** Message for the person if the preferences file was unreadable; else null. */
    String preferencesWarning();
    /**
     * {@code warn} gets a message for the person when saves start failing (again only after one worked), on a thread of
     * its own; null stops it.
     */
    void onSaveFailure(Consumer<String> warn);
}
