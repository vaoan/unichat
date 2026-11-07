package app.unichat.service;

import app.unichat.model.Chat;
import app.unichat.support.I18n;

import java.util.List;
import java.util.function.Consumer;

/** {@link ChatFolders} over a {@link PreferencesFile}, shared by both services. Every change says {@code chatsChanged}. */
public final class FolderActions implements ChatFolders {
    private final PreferencesFile prefs;
    private final Runnable fireChatsChanged;

    public FolderActions(PreferencesFile prefs, Runnable fireChatsChanged) {
        this.prefs = prefs;
        this.fireChatsChanged = fireChatsChanged;
    }

    @Override public List<Folder> folders() { return prefs.folders(); }

    @Override
    public String createFolder(String name) {
        String id = prefs.createFolder(name);
        fireChatsChanged.run();
        return id;
    }

    @Override
    public void renameFolder(String id, String name) {
        prefs.renameFolder(id, name);
        fireChatsChanged.run();
    }

    @Override
    public void deleteFolder(String id) {
        prefs.deleteFolder(id);
        fireChatsChanged.run();
    }

    @Override
    public void setInFolder(String folderId, Chat chat, boolean in) {
        if (chat.prefKey() == null) return;
        prefs.setInFolder(folderId, chat.prefKey(), in);
        fireChatsChanged.run();
    }

    @Override
    public String preferencesWarning() {
        String aside = prefs.setAside();
        if (prefs.recoveredFromBackup()) {
            String kept = prefs.unreadable() ? I18n.t("folders.warn.keptNot")
                    : I18n.t("folders.warn.keptAs", aside);
            return I18n.t("folders.warn.recovered", kept);
        }
        if (prefs.unreadable()) {
            return I18n.t("folders.warn.unreadable");
        }
        return aside == null ? null : I18n.t("folders.warn.setAside", aside);
    }

    @Override
    public void onSaveFailure(Consumer<String> warn) {
        // One toast at a time, and a failure right at start would replace the recovery's: it says both.
        prefs.onSaveFailure(warn == null ? null
                : () -> warn.accept(prefs.recoveredFromBackup() ? preferencesWarning() + " " + I18n.t("folders.saveFailed") : I18n.t("folders.saveFailed")));
    }
}
