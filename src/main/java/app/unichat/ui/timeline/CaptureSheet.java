package app.unichat.ui.timeline;

import app.unichat.model.Chat;
import app.unichat.service.ChatService;
import app.unichat.service.SendAs;
import app.unichat.support.Errors;
import app.unichat.support.I18n;
import app.unichat.ui.Chips;
import app.unichat.ui.Sheets;
import app.unichat.ui.Showing;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A capture of a stretch of a chat (picked between two markers): its preview, as one long picture or pieces a phone's
 * screen tall (cut between messages), a footer saying where and when, and what to do with it: send it to chats (what's
 * done most), copy it, or save it to a folder. In the window's overlay.
 */
public final class CaptureSheet {
    private static final double WIDTH = 460, PREVIEW_WIDTH = 380, PREVIEW_HEIGHT = 420;

    /** Where what's made for sending goes (swept after a day, `OutgoingPhotos.prune`): a folder for each capture. */
    private static Path sending() {
        Path root = app.unichat.settings.Settings.dataDir().resolve("cache").resolve("sending");
        app.unichat.support.Folders.deleteOlderThan(root, java.time.Duration.ofDays(1)); // also when no photo is sent
        return root.resolve("capture-" + System.nanoTime());
    }

    /** How it goes: one long picture, or pieces a phone's screen tall. */
    enum Format { LONG, PHONE }

    private final ChatService svc;
    private final Consumer<Node> show;
    private final Runnable hide;
    private final Consumer<String> toast;
    private Function<Window, File> chooseFolder = w -> {
        javafx.stage.DirectoryChooser c = new javafx.stage.DirectoryChooser();
        c.setTitle(I18n.t("capture.save"));
        return c.showDialog(w);
    };

    public CaptureSheet(ChatService svc, Consumer<Node> show, Runnable hide, Consumer<String> toast) {
        this.svc = svc;
        this.show = show;
        this.hide = hide;
        this.toast = toast;
    }

    /** Tests: where "Guardar…" saves, without a folder dialog. */
    void useFolderChooser(Function<Window, File> choose) {
        chooseFolder = choose;
    }

    /**
     * Opens it for {@code count} messages of {@code chat}; {@code take} makes the capture (with or without the footer)
     * as the chat draws it.
     */
    void open(int count, Chat chat, Function<Boolean, CompletableFuture<ConversationShot.Shot>> take) {
        new Open(count, chat, take).start();
    }

    /** One capture being looked at: its options, what was made, and the card showing it. */
    private final class Open {
        final Chat chat;
        final Function<Boolean, CompletableFuture<ConversationShot.Shot>> take;
        final VBox card = new VBox(12);
        final Label title;
        final Label status = new Label(I18n.t("capture.preparing"));
        final ProgressBar working = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        final VBox preview = new VBox(6);
        final ToggleGroup formats = new ToggleGroup();
        final CheckBox footer = new CheckBox(I18n.t("capture.footerOption"));
        final Button send = new Button(I18n.t("capture.sendToChats"));
        final Button copy = new Button(I18n.t("capture.copy"));
        final Button save = new Button(I18n.t("capture.save"));
        ConversationShot.Shot shot;
        List<Image> pictures = List.of();
        /** Which take is the latest: an older one finishing later (the footer changed twice) is ignored. */
        int takes;

        Open(int count, Chat chat, Function<Boolean, CompletableFuture<ConversationShot.Shot>> take) {
            this.chat = chat;
            this.take = take;
            title = new Label(I18n.n("capture.title", count));
        }

        void start() {
            title.getStyleClass().add("sheet-title");
            status.setWrapText(true);
            working.setMaxWidth(Double.MAX_VALUE);
            Showing.spinWhileSeen(working);
            ToggleButton lon = Chips.sticky(I18n.t("capture.long"), formats, true, this::refresh);
            lon.setUserData(Format.LONG);
            ToggleButton phone = Chips.sticky(I18n.t("capture.phone"), formats, false, this::refresh);
            phone.setUserData(Format.PHONE);
            FlowPane formatRow = new FlowPane(6, 6, lon, phone);
            formatRow.setAccessibleText(I18n.t("capture.format"));
            footer.setSelected(true);
            footer.setOnAction(e -> retake());
            ScrollPane scroll = new ScrollPane(preview);
            scroll.setFitToWidth(true);
            scroll.setPrefViewportHeight(PREVIEW_HEIGHT);
            scroll.getStyleClass().add("capture-preview");
            preview.setAlignment(Pos.TOP_CENTER);
            send.getStyleClass().add("primary-button");
            send.setDefaultButton(true);
            send.setOnAction(e -> chooseChats());
            copy.getStyleClass().add("secondary-button");
            copy.setOnAction(e -> copy());
            save.getStyleClass().add("secondary-button");
            save.setOnAction(e -> save());
            Button close = new Button(I18n.t("info.close"));
            close.getStyleClass().add("secondary-button");
            close.setCancelButton(true);
            close.setOnAction(e -> hide.run());
            FlowPane buttons = new FlowPane(8, 6, close, save, copy, send);
            buttons.setAlignment(Pos.CENTER_RIGHT);
            card.getChildren().setAll(title, formatRow, footer, status, working, scroll, buttons);
            card.getStyleClass().addAll("card", "folder-dialog", "capture-sheet");
            show.accept(Sheets.scrolling(card, WIDTH));
            retake();
            Platform.runLater(send::requestFocus);
        }

        Format format() {
            return formats.getSelectedToggle() != null && formats.getSelectedToggle().getUserData() == Format.PHONE ? Format.PHONE : Format.LONG;
        }

        /** Makes the capture again (the footer changed, or it's the first time). */
        void retake() {
            ready(false);
            int mine = ++takes;
            CompletableFuture<ConversationShot.Shot> made;
            try {
                made = take.apply(footer.isSelected());
            } catch (RuntimeException e) {
                made = CompletableFuture.failedFuture(e);
            }
            var _ = made.whenComplete((s, err) -> Platform.runLater(() -> {
                if (mine != takes) return; // a newer one is on its way
                if (err != null) {
                    status.setText(I18n.t("capture.failed", Errors.message(err)));
                    app.unichat.ui.Nodes.show(working, false);
                    return;
                }
                shot = s;
                refresh();
            }));
        }

        /** The pictures for the format chosen, shown. */
        void refresh() {
            if (shot == null) return;
            pictures = format() == Format.LONG ? List.of(shot.whole())
                    : ConversationShot.pieces(shot, ConversationShot.phoneHeight((int) shot.whole().getWidth()));
            preview.getChildren().clear();
            for (int i = 0; i < pictures.size(); i++) {
                ImageView v = new ImageView(ConversationShot.small(pictures.get(i), (int) PREVIEW_WIDTH));
                v.setFitWidth(PREVIEW_WIDTH);
                v.setPreserveRatio(true);
                v.getStyleClass().add("capture-picture");
                v.setAccessibleText(I18n.t("capture.picture", i + 1, pictures.size()));
                preview.getChildren().add(v);
            }
            status.setText(I18n.n("capture.ready", pictures.size()));
            ready(true);
        }

        void ready(boolean on) {
            app.unichat.ui.Nodes.show(working, !on);
            if (!on) status.setText(I18n.t("capture.preparing"));
            for (Button b : List.of(send, copy, save)) b.setDisable(!on);
        }

        /**
         * The pictures as PNG files, in order, into {@code dir} (never over a file there); null: a folder of their own
         * with what's made for sending, which is swept after a day: a capture of a private chat never stays for good.
         */
        CompletableFuture<List<Path>> files(Path dir) {
            List<Image> now = pictures;
            String base = I18n.t("capture.fileName", app.unichat.autosave.FileNames.folder(chat.title()), LocalDate.now(ZoneId.systemDefault()));
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Path into = dir != null ? dir : Files.createDirectories(sending());
                    List<Path> out = new ArrayList<>();
                    for (int i = 0; i < now.size(); i++) {
                        Path f = app.unichat.autosave.FileNames.free(into, base + (now.size() > 1 ? " (" + (i + 1) + ")" : "") + ".png");
                        javax.imageio.ImageIO.write(SwingFXUtils.fromFXImage(now.get(i), null), "png", f.toFile());
                        out.add(f);
                    }
                    return out;
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }

        void copy() {
            List<Image> now = pictures;
            var _ = files(null).whenComplete((fs, err) -> Platform.runLater(() -> {
                if (err != null) {
                    toast.accept(I18n.t("capture.failed", Errors.message(err)));
                    return;
                }
                var c = new ClipboardContent();
                if (now.size() == 1) c.putImage(now.getFirst()); // pasted as a picture where one goes
                c.putFiles(fs.stream().map(Path::toFile).toList());
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
                toast.accept(I18n.n("capture.copied", fs.size()));
            }));
        }

        void save() {
            File folder = chooseFolder.apply(card.getScene() == null ? null : card.getScene().getWindow());
            if (folder == null) return;
            // Straight into the folder chosen: no copy of the chat left anywhere else.
            var _ = files(folder.toPath()).whenComplete((fs, err) -> Platform.runLater(() -> toast.accept(err != null
                    ? I18n.t("capture.failed", Errors.message(err)) : I18n.n("capture.saved", fs.size(), folder.getPath()))));
        }

        /** The card becomes the chats to send to; "Volver" brings the preview back. */
        void chooseChats() {
            List<Node> before = new ArrayList<>(card.getChildren());
            Button go = new Button(I18n.t("capture.send"));
            ChatChooser chats = new ChatChooser(svc, to -> go.setDisable(to.isEmpty()));
            Label which = new Label(I18n.t("capture.sendTitle"));
            which.getStyleClass().add("sheet-title");
            Button back = new Button(I18n.t("capture.back"));
            back.getStyleClass().add("secondary-button");
            back.setCancelButton(true);
            back.setOnAction(e -> card.getChildren().setAll(before));
            go.getStyleClass().add("primary-button");
            go.setDefaultButton(true);
            go.setOnAction(e -> {
                List<String> to = chats.chosen();
                hide.run();
                sendTo(to);
            });
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            HBox buttons = new HBox(10, chats.count(), grow, back, go);
            buttons.setAlignment(Pos.CENTER_LEFT);
            card.getChildren().setAll(which, chats.search(), chats.list(), buttons);
            Platform.runLater(chats::focus);
        }

        /**
         * Each chat gets the pictures in order, through the upload queue (its bar, its pause between files, trying again,
         * what went and what didn't): pieces as photos; one long picture as a document, as a photo the network shrank
         * it to a strip no one can read (a photo's longest side is 2560 px).
         */
        void sendTo(List<String> to) {
            SendAs as = format() == Format.LONG ? SendAs.DOCUMENT : SendAs.PHOTO;
            var _ = files(null).whenComplete((fs, err) -> Platform.runLater(() -> {
                if (err != null) {
                    toast.accept(I18n.t("capture.failed", Errors.message(err)));
                    return;
                }
                List<app.unichat.service.UploadQueue.Upload> uploads = new ArrayList<>();
                for (String chatId : to) for (Path f : fs) uploads.add(new app.unichat.service.UploadQueue.Upload(chatId, f, null, as));
                app.unichat.service.UploadQueue.of(svc).addAll(uploads);
                toast.accept(I18n.n("capture.sending", to.size()));
            }));
        }
    }
}
