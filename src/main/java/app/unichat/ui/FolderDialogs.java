package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.service.ChatFolders;
import app.unichat.service.PreferencesFile;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/** The sheets folders ask with, in the window's overlay like the rest of the app (a separate window would take the focus). */
final class FolderDialogs {
    private static final double WIDTH = 420;
    private final ChatFolders folders;
    private final Consumer<Node> show;
    private final Runnable hide;

    FolderDialogs(ChatFolders folders, Consumer<Node> show, Runnable hide) {
        this.folders = folders;
        this.show = show;
        this.hide = hide;
    }

    ChatFolders folders() { return folders; }

    /** {@code after} runs however the sheet closes (also Esc or a click outside): it's where the focus goes back. */
    void create(Runnable after) {
        askName("", "Crear", folders::createFolder, after);
    }

    /** "Nueva carpeta…" in a chat's menu: the new folder starts with that chat. */
    void createWith(Chat chat) {
        askName("", "Crear", name -> folders.setInFolder(folders.createFolder(name), chat, true), () -> { });
    }

    void rename(PreferencesFile.Folder folder, Runnable after) {
        askName(folder.name(), "Guardar", name -> folders.renameFolder(folder.id(), name), after);
    }

    void delete(PreferencesFile.Folder folder, Runnable after) {
        Label question = new Label("¿Eliminar la carpeta «" + folder.name() + "»? Los chats no se borran.");
        question.getStyleClass().add("sheet-title");
        question.setWrapText(true);
        Button cancel = cancelButton();
        cancel.setAccessibleHelp(question.getText()); // the focus starts here, so a screen reader says what's being asked
        Button delete = new Button("Eliminar");
        delete.getStyleClass().add("danger-button");
        delete.setOnAction(e -> {
            folders.deleteFolder(folder.id());
            hide.run();
        });
        open(after, cancel, question, buttons(cancel, delete));
    }

    private void askName(String current, String action, Consumer<String> done, Runnable after) {
        Label title = new Label("Nombre de la carpeta");
        title.getStyleClass().add("sheet-title");
        TextField field = new TextField(current);
        field.setAccessibleText("Nombre de la carpeta");
        Button save = new Button(action);
        save.getStyleClass().add("primary-button");
        save.setDefaultButton(true);
        save.disableProperty().bind(Bindings.createBooleanBinding(() -> field.getText().isBlank(), field.textProperty()));
        save.setOnAction(e -> {
            done.accept(field.getText());
            hide.run();
        });
        field.setOnAction(e -> save.fire());
        open(after, field, title, field, buttons(cancelButton(), save));
    }

    private Button cancelButton() {
        Button cancel = new Button("Cancelar");
        cancel.getStyleClass().add("secondary-button");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> hide.run());
        return cancel;
    }

    private static HBox buttons(Button... buttons) {
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox row = new HBox(10, grow);
        row.getChildren().addAll(buttons);
        return row;
    }

    private void open(Runnable after, Node focus, Node... content) {
        VBox card = new VBox(content);
        card.getStyleClass().addAll("card", "folder-dialog");
        card.setPrefWidth(WIDTH);
        card.sceneProperty().addListener((o, a, scene) -> {
            if (scene == null) Platform.runLater(after);
        });
        // In a Group: the overlay stretches a Region to the window's height, and this is a small question.
        show.accept(new Group(card));
        Platform.runLater(focus::requestFocus);
    }
}
