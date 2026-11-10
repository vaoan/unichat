package app.unichat.ui;

import app.unichat.cloak.CloakJob;
import app.unichat.cloak.CloakLevel;
import app.unichat.cloak.CloakTool;
import app.unichat.service.ChatMessaging;
import app.unichat.service.SendAs;
import app.unichat.service.UploadQueue;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Foto protegida contra IA": how hard (a sheet in the window's overlay, which also says what the first time
 * downloads), then the picture; {@link CloakJob} protects it with {@link CloakBar} on top, and it goes to the chat as a
 * file through the upload queue.
 */
final class CloakSend {
    private static final double WIDTH = 460;
    private static final String LEVEL_KEY = "cloak.level";

    private final ChatMessaging svc;
    private final Consumer<Node> show;
    private final Runnable hide;
    private final Consumer<String> toast;

    CloakSend(ChatMessaging svc, Consumer<Node> show, Runnable hide, Consumer<String> toast) {
        this.svc = svc;
        this.show = show;
        this.hide = hide;
        this.toast = toast;
    }

    /** Asks for the level, then the picture, for {@code chatId}; {@code after} runs once the sheet closes. */
    void open(String chatId, Runnable after) {
        if (chatId == null) return;
        if (!CloakTool.canInstall()) {
            toast.accept(I18n.t("cloak.unsupported"));
            return;
        }
        if (CloakJob.get().status().busy()) {
            toast.accept(I18n.t("cloak.busy"));
            return;
        }
        Label title = new Label(I18n.t("cloak.title"));
        title.getStyleClass().add("sheet-title");
        Label intro = wrapped(I18n.t("cloak.intro"));
        ToggleGroup levels = new ToggleGroup();
        VBox choices = new VBox(8);
        CloakLevel chosen = chosenLevel();
        for (CloakLevel l : CloakLevel.values()) {
            RadioButton r = new RadioButton(I18n.t("cloak.level." + l.name()));
            r.setToggleGroup(levels);
            r.setUserData(l);
            r.setSelected(l == chosen);
            Label help = wrapped(I18n.t("cloak.level." + l.name() + ".help"));
            help.getStyleClass().add("settings-help");
            r.setAccessibleHelp(help.getText());
            choices.getChildren().add(new VBox(2, r, help));
        }
        VBox card = new VBox(12, title, intro, choices);
        if (!CloakTool.standard().isInstalled()) card.getChildren().add(wrapped(I18n.t("cloak.download", CloakTool.downloadMb())));
        Button cancel = new Button(I18n.t("folders.dialog.cancel"));
        cancel.getStyleClass().add("secondary-button");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> hide.run());
        Button go = new Button(I18n.t("cloak.choose"));
        go.getStyleClass().add("primary-button");
        go.setDefaultButton(true);
        go.setMinWidth(Region.USE_PREF_SIZE);
        cancel.setMinWidth(Region.USE_PREF_SIZE);
        go.setOnAction(e -> {
            CloakLevel level = (CloakLevel) levels.getSelectedToggle().getUserData();
            Settings.get().set(LEVEL_KEY, level.name());
            javafx.stage.Window w = go.getScene().getWindow();
            hide.run();
            choosePicture(w, chatId, level);
        });
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        card.getChildren().add(new HBox(10, grow, cancel, go));
        card.getStyleClass().addAll("card", "folder-dialog");
        card.setPrefWidth(WIDTH);
        card.sceneProperty().addListener((o, a, scene) -> {
            if (scene == null) Platform.runLater(after);
        });
        show.accept(new Group(card));
        Platform.runLater(() -> ((Node) levels.getSelectedToggle()).requestFocus());
    }

    private void choosePicture(javafx.stage.Window w, String chatId, CloakLevel level) {
        FileChooser fc = new FileChooser();
        fc.setTitle(I18n.t("cloak.choose"));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.t("composer.attach.photos"),
                "*.jpg", "*.jpeg", "*.png", "*.webp", "*.bmp", "*.gif"));
        File f = fc.showOpenDialog(w);
        if (f == null) return;
        Path out = output(f.toPath());
        boolean started = CloakJob.get().start(f.toPath(), out, level, done ->
                UploadQueue.of(svc).addAll(List.of(new UploadQueue.Upload(chatId, done, null, SendAs.DOCUMENT))));
        if (!started) toast.accept(I18n.t("cloak.busy"));
    }

    /** With the photos made for sending (deleted after a day), under the picture's own name as a PNG. */
    static Path output(Path in) {
        String name = in.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return Settings.dataDir().resolve("cache").resolve("sending").resolve("protected-" + System.currentTimeMillis())
                .resolve(base + ".png");
    }

    private static CloakLevel chosenLevel() {
        try {
            return CloakLevel.valueOf(Settings.get().getString(LEVEL_KEY, CloakLevel.MEDIUM.name()));
        } catch (IllegalArgumentException e) {
            return CloakLevel.MEDIUM;
        }
    }

    private static Label wrapped(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        return l;
    }
}
