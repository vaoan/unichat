package app.unichat.service;

import app.unichat.model.Chat;

import java.util.List;
import java.util.function.Consumer;

/** {@link ChatFolders} over a {@link PreferencesFile}, shared by both services. Every change says {@code chatsChanged}. */
public final class FolderActions implements ChatFolders {
    private static final String SAVE_FAILED = "No se pudieron guardar tus preferencias. Lo que cambies puede perderse al cerrar.";

    private final PreferencesFile prefs;
    private final Runnable fireChatsChanged;

    public FolderActions(PreferencesFile prefs, Runnable fireChatsChanged) {
        this.prefs = prefs;
        this.fireChatsChanged = fireChatsChanged;
    }

    @Override public List<PreferencesFile.Folder> folders() { return prefs.folders(); }

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
            String kept = prefs.unreadable() ? "no se pudo guardar aparte y no se guardará nada hasta que se pueda"
                    : "se guardó aparte como " + aside;
            return "preferences.json estaba dañado (" + kept + "). Se recuperó la copia anterior: puede faltar tu último cambio.";
        }
        if (prefs.unreadable()) {
            return "No se pudo leer preferences.json y no se pudo guardar aparte: no se guardará nada hasta que se pueda.";
        }
        return aside == null ? null : "No se pudo leer preferences.json. Se guardó aparte como " + aside
                + "; tus preferencias empiezan vacías.";
    }

    @Override
    public void onSaveFailure(Consumer<String> warn) {
        // One toast at a time, and a failure right at start would replace the recovery's: it says both.
        prefs.onSaveFailure(warn == null ? null
                : () -> warn.accept(prefs.recoveredFromBackup() ? preferencesWarning() + " " + SAVE_FAILED : SAVE_FAILED));
    }
}
