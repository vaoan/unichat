package app.unichat.service;

import app.unichat.model.Chat;

import java.util.List;
import java.util.function.Consumer;

/**
 * An implementation mixin, not a role: both services get {@link ChatFolders} by handing every call to one
 * {@link FolderActions}. Callers take {@link ChatFolders}, never this.
 */
public interface FolderForwarding extends ChatFolders {
    ChatFolders folderActions();

    @Override default List<PreferencesFile.Folder> folders() { return folderActions().folders(); }
    @Override default String createFolder(String name) { return folderActions().createFolder(name); }
    @Override default void renameFolder(String id, String name) { folderActions().renameFolder(id, name); }
    @Override default void deleteFolder(String id) { folderActions().deleteFolder(id); }
    @Override default void setInFolder(String folderId, Chat chat, boolean in) { folderActions().setInFolder(folderId, chat, in); }
    @Override default String preferencesWarning() { return folderActions().preferencesWarning(); }
    @Override default void onSaveFailure(Consumer<String> warn) { folderActions().onSaveFailure(warn); }
}
