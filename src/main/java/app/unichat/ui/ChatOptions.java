package app.unichat.ui;

import app.unichat.autosave.AutoSave;
import app.unichat.autosave.SaveFolders;
import app.unichat.model.Chat;
import app.unichat.model.SaveKind;
import app.unichat.service.ChatService;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A chat's own options, from the gear in its header, in the window's overlay: what of what others send there is saved
 * as it arrives ({@link AutoSave}), each kind apart, and the folder it goes to. Changes apply at once.
 */
final class ChatOptions {
    private static final double WIDTH = 440;

    private final ChatService svc;
    private final Consumer<Node> show;
    private final Runnable hide;
    private final Consumer<String> toast;

    ChatOptions(ChatService svc, Consumer<Node> show, Runnable hide, Consumer<String> toast) {
        this.svc = svc;
        this.show = show;
        this.hide = hide;
        this.toast = toast;
    }

    /** {@code after} runs however the sheet closes: it's where the focus goes back. */
    void open(Chat chat, Runnable after) {
        Label title = new Label(I18n.t("chatOptions.title", chat.title()));
        title.getStyleClass().add("sheet-title");
        title.setWrapText(true);
        Set<SaveKind> saved = svc.autoSaves(chat.id());
        CheckBox on = new CheckBox(I18n.t("chatOptions.autoSave"));
        on.setSelected(!saved.isEmpty());
        on.setWrapText(true);
        Map<SaveKind, CheckBox> kinds = new EnumMap<>(SaveKind.class);
        VBox kindBoxes = new VBox(6);
        kindBoxes.getStyleClass().add("chat-options-kinds");
        for (SaveKind k : SaveKind.values()) {
            CheckBox b = new CheckBox(I18n.t("chatOptions.kind." + k.name()));
            b.setSelected(saved.isEmpty() || saved.contains(k)); // turning it on starts with everything
            b.disableProperty().bind(on.selectedProperty().not());
            b.setWrapText(true);
            kinds.put(k, b);
            kindBoxes.getChildren().add(b);
        }
        Label help = Labels.help(I18n.t("chatOptions.autoSaveHelp"));
        on.setAccessibleHelp(help.getText());
        Runnable apply = () -> apply(chat, on, kinds);
        on.setOnAction(e -> {
            if (on.isSelected()) {
                if (kinds.values().stream().noneMatch(CheckBox::isSelected)) kinds.values().forEach(b -> b.setSelected(true));
                AutoSave.of(svc).startNow(chat.id()); // what's there already stays out
            }
            apply.run();
        });
        kinds.values().forEach(b -> b.setOnAction(e -> apply.run()));
        Button openFolder = new Button(I18n.t("chatOptions.openFolder"));
        openFolder.getStyleClass().add("secondary-button");
        openFolder.setOnAction(e -> openFolder(SaveFolders.of(chat)));
        Button done = new Button(I18n.t("chatOptions.done"));
        done.getStyleClass().add("primary-button");
        done.setDefaultButton(true);
        done.setCancelButton(true);
        done.setOnAction(e -> hide.run());
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        CheckBox showMedia = new CheckBox(I18n.t("chatOptions.showMedia"));
        showMedia.setSelected(!svc.mediaHidden(chat.id()));
        showMedia.setWrapText(true);
        showMedia.setOnAction(e -> svc.setMediaHidden(chat.id(), !showMedia.isSelected()));
        VBox card = new VBox(12, title, sectionTitle(I18n.t("chatOptions.profileTitle")), showMedia,
                sectionTitle(I18n.t("chatOptions.autoSaveTitle")), on, kindBoxes, help,
                folderRow(chat), sectionTitle(I18n.t("chatOptions.pastTitle")), past(chat, on),
                new HBox(10, openFolder, grow, done));
        card.getStyleClass().addAll("card", "folder-dialog", "chat-options");
        card.setPrefWidth(WIDTH);
        card.sceneProperty().addListener((o, a, scene) -> {
            if (scene == null) Platform.runLater(after);
        });
        // Scrolls when taller than the window (big text): the overlay keeps a Region within its height, never a Group.
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(card);
        scroll.setFitToWidth(true);
        scroll.setMaxWidth(WIDTH + 24);
        scroll.setMaxHeight(Region.USE_PREF_SIZE);
        scroll.getStyleClass().addAll("side-panel-scroll", "chat-options-scroll");
        show.accept(scroll);
        Platform.runLater(on::requestFocus);
    }

    /**
     * "Lo anterior": going back through the chat's history to its beginning, saving the kinds chosen of what's still
     * there ({@link AutoSave#explore}); how far it got, and a way to stop it. Only while saving is on.
     */
    private Node past(Chat chat, CheckBox on) {
        AutoSave save = AutoSave.of(svc);
        Label help = Labels.help(I18n.t("chatOptions.pastHelp"));
        Label state = new Label();
        state.setWrapText(true);
        state.setMinHeight(Region.USE_PREF_SIZE);
        Button go = new Button();
        go.getStyleClass().add("secondary-button");
        Runnable redraw = () -> {
            AutoSave.Exploring e = save.exploring(chat.id());
            go.setText(I18n.t(e.running() ? "chatOptions.pastStop" : "chatOptions.pastStart"));
            state.setText(pastState(e));
            Nodes.show(state, !state.getText().isEmpty());
        };
        go.setOnAction(e -> {
            if (save.exploring(chat.id()).running()) save.stopExploring(chat.id());
            else save.explore(chat.id());
            redraw.run();
        });
        go.disableProperty().bind(on.selectedProperty().not());
        Runnable listener = () -> Platform.runLater(redraw);
        save.addExploringListener(listener);
        state.sceneProperty().addListener((o, a, scene) -> {
            if (scene == null) save.removeExploringListener(listener);
        });
        redraw.run();
        return new VBox(6, help, go, state);
    }

    static String pastState(AutoSave.Exploring e) {
        String counts = e.saved() + e.unavailable() == 0 ? "" : " " + I18n.t("chatOptions.pastCounts", e.saved(), e.unavailable());
        if (e.running()) return I18n.t("chatOptions.pastRunning") + counts;
        if (e.start()) return I18n.t("chatOptions.pastAtStart") + counts;
        if (e.back() != null) {
            String day = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy").withLocale(I18n.locale())
                    .format(e.back().atZone(java.time.ZoneId.systemDefault()));
            return I18n.t("chatOptions.pastUntil", day) + counts;
        }
        return "";
    }

    /** "Carpeta: ~/Downloads/Unichat/Familia", to change for this chat alone or bring back under the general one. */
    private Node folderRow(Chat chat) {
        Label where = new Label();
        where.setWrapText(true);
        where.setMinHeight(Region.USE_PREF_SIZE);
        Button change = new Button(I18n.t("chatOptions.changeFolder"));
        change.getStyleClass().add("secondary-button");
        Button general = new Button(I18n.t("chatOptions.generalFolder"));
        general.getStyleClass().add("secondary-button");
        Runnable redraw = () -> {
            where.setText(I18n.t("chatOptions.folder", SaveFolders.shown(SaveFolders.of(chat))));
            Nodes.show(general, SaveFolders.hasOwn(chat.id()));
        };
        change.setOnAction(e -> {
            javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser();
            dc.setTitle(I18n.t("chatOptions.changeFolder"));
            java.io.File f = dc.showDialog(change.getScene().getWindow());
            if (f == null) return;
            SaveFolders.setOwn(chat.id(), f.toPath());
            redraw.run();
        });
        general.setOnAction(e -> {
            SaveFolders.setOwn(chat.id(), null);
            redraw.run();
        });
        redraw.run();
        javafx.scene.layout.FlowPane buttons = new javafx.scene.layout.FlowPane(8, 6, change, general);
        return new VBox(6, where, buttons);
    }

    private void apply(Chat chat, CheckBox on, Map<SaveKind, CheckBox> kinds) {
        Set<SaveKind> chosen = EnumSet.noneOf(SaveKind.class);
        if (on.isSelected()) kinds.forEach((k, b) -> { if (b.isSelected()) chosen.add(k); });
        svc.setAutoSave(chat.id(), chosen);
    }

    private void openFolder(Path folder) {
        try {
            Files.createDirectories(folder); // nothing has arrived yet: an empty folder to look at
            Browser.openFolder(folder, () -> Platform.runLater(() -> toast.accept(I18n.t("chatOptions.openFolderFailed"))));
        } catch (java.io.IOException ex) {
            toast.accept(I18n.t("chatOptions.openFolderFailed"));
        }
    }

    private static Label sectionTitle(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("emoji-category");
        return l;
    }
}
