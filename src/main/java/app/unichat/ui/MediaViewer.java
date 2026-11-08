package app.unichat.ui;

import app.unichat.support.I18n;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Full-size image viewer window with zoom, turn and save controls; Esc or a click beside the picture closes it. It
 * opens at once with a spinner while the full image downloads (a click that showed nothing for seconds looked like
 * it didn't work), and plays animated WebP too.
 */
public final class MediaViewer {
    private static final double ARROW_STEP = 60;

    private MediaViewer() {}

    /** One picture the viewer can show: its name, how to get the file (only when shown), and how to save it. */
    record Item(String title, java.util.function.Supplier<CompletableFuture<Path>> file, Runnable onSave) {}

    /** {@code onSave}: the "Guardar como…" button. */
    public static void show(Window owner, CompletableFuture<Path> file, String title, Runnable onSave) {
        show(owner, java.util.List.of(new Item(title == null ? I18n.t("media.image") : title, () -> file, onSave)), 0);
    }

    /**
     * Shows {@code items.get(start)}; ← and → (or the side buttons, Page Up and Page Down) go to the one before and
     * after, the arrows pan instead while the picture is zoomed in. F or the button: full screen; Esc leaves it, then closes.
     */
    static void show(Window owner, java.util.List<Item> items, int start) {
        ProgressIndicator spin = new ProgressIndicator();
        spin.setMaxSize(40, 40);
        StackPane area = new StackPane(spin);
        Stage stage = new Stage();
        PhotoPane photo = new PhotoPane(items.get(start).title(), stage::close);
        int[] at = {start};
        long[] generation = {0};
        Label counter = new Label();
        counter.getStyleClass().add("media-viewer-percent");
        Button full = Icons.iconButton(Icons.FULLSCREEN, I18n.t("video.fullScreen"), () -> stage.setFullScreen(!stage.isFullScreen()));
        Runnable[] go = new Runnable[1];
        Button prev = Icons.iconButton(Icons.CHEVRON_LEFT, I18n.t("viewer.previous"), () -> go(items, at, -1, go[0]));
        Button next = Icons.iconButton(Icons.CHEVRON_RIGHT, I18n.t("viewer.next"), () -> go(items, at, 1, go[0]));
        for (Button b : java.util.List.of(prev, next)) {
            b.getStyleClass().add("viewer-nav");
            Nodes.show(b, items.size() > 1);
        }
        StackPane.setAlignment(prev, Pos.CENTER_LEFT);
        StackPane.setAlignment(next, Pos.CENTER_RIGHT);
        StackPane.setMargin(prev, new javafx.geometry.Insets(0, 0, 0, 12));
        StackPane.setMargin(next, new javafx.geometry.Insets(0, 12, 0, 0));
        FlowPane bar = bar(photo, () -> items.get(at[0]).onSave().run(), counter, full);
        Nodes.show(counter, items.size() > 1);
        StackPane middle = new StackPane(area, prev, next);
        BorderPane root = new BorderPane(middle);
        root.setBottom(bar);
        root.getStyleClass().add("media-viewer");
        stage.initOwner(owner);
        Scene scene = new Scene(root, Math.min(900, owner.getWidth() * 0.8), Math.min(700, owner.getHeight() * 0.8));
        Theme.register(scene);
        stage.setFullScreenExitKeyCombination(javafx.scene.input.KeyCombination.NO_MATCH); // Esc is ours, below
        stage.setFullScreenExitHint(I18n.t("video.fullScreenHint"));
        stage.fullScreenProperty().addListener((o, a, on) -> {
            full.setGraphic(Icons.icon(on ? Icons.FULLSCREEN_EXIT : Icons.FULLSCREEN, 18));
            full.setAccessibleText(I18n.t(on ? "video.exitFullScreen" : "video.fullScreen"));
            full.getTooltip().setText(full.getAccessibleText());
        });
        // A filter: the arrows would otherwise move the focus between the buttons.
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                if (stage.isFullScreen()) stage.setFullScreen(false);
                else stage.close();
            } else if (e.getCode() == KeyCode.F && !e.isShortcutDown()) {
                stage.setFullScreen(!stage.isFullScreen());
                e.consume();
            } else if (navigation(e, photo, items, at, go[0]) || (area.getChildren().contains(photo) && key(photo, e))) {
                e.consume();
            }
        });
        area.setOnMouseClicked(e -> { if (e.getClickCount() == 1 && e.getTarget() == area) stage.close(); });
        stage.setScene(scene);

        go[0] = () -> {
            long mine = ++generation[0];
            Item item = items.get(at[0]);
            stage.setTitle(item.title());
            counter.setText(I18n.t("viewer.position", at[0] + 1, items.size()));
            prev.setDisable(at[0] == 0);
            next.setDisable(at[0] == items.size() - 1);
            photo.setAccessibleText(item.title());
            area.getChildren().setAll(spin);
            item.file().get().whenComplete((p, err) -> {
                try {
                    if (err != null || p == null) throw new IOException("no file", err);
                    load(p, () -> {
                        if (generation[0] != mine) return false;
                        photo.view().setImage(null); // a new picture: fitted and unturned, even if the last one was the same size
                        area.getChildren().setAll(photo);
                        return true;
                    }, photo);
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        if (generation[0] != mine) return;
                        Label l = new Label(I18n.t("viewer.openFailed"));
                        l.getStyleClass().add("settings-help");
                        area.getChildren().setAll(l);
                    });
                }
            });
            // The next one starts downloading now: stepping through a chat shouldn't wait on each.
            if (at[0] + 1 < items.size()) items.get(at[0] + 1).file().get();
        };
        stage.show();
        go[0].run();
    }

    /** One picture before ({@code by} -1) or after (1), if there is one. */
    private static void go(java.util.List<Item> items, int[] at, int by, Runnable show) {
        int to = at[0] + by;
        if (to < 0 || to >= items.size()) return;
        at[0] = to;
        show.run();
    }

    /** ← and → step through the pictures unless the picture is zoomed in (then they pan); Page Up/Down, Home and End always. */
    private static boolean navigation(KeyEvent e, PhotoPane photo, java.util.List<Item> items, int[] at, Runnable show) {
        if (items.size() < 2 || e.isShortcutDown()) return false;
        boolean panning = !photo.zoom().isFitted() && photo.zoom().canPan();
        switch (e.getCode()) {
            case LEFT -> { if (panning) return false; go(items, at, -1, show); }
            case RIGHT -> { if (panning) return false; go(items, at, 1, show); }
            case PAGE_UP -> go(items, at, -1, show);
            case PAGE_DOWN -> go(items, at, 1, show);
            case HOME -> go(items, at, -at[0], show);
            case END -> go(items, at, items.size() - 1 - at[0], show);
            default -> { return false; }
        }
        return true;
    }

    /** Decodes here, off the UI thread, then shows it, unless {@code reveal} says another picture was asked for meanwhile. */
    private static void load(Path p, java.util.function.BooleanSupplier reveal, PhotoPane photo) throws IOException {
        if (AnimatedImage.isWebp(p) || AnimatedImage.isGif(p)) {
            // WebP JavaFX can't read; GIFs it animates forever, leaking every frame (AnimatedImage).
            AnimatedImage.Frames frames = AnimatedImage.decode(p, 1600, 1600);
            Platform.runLater(() -> {
                if (reveal.getAsBoolean()) AnimatedImage.play(photo.view(), frames);
            });
        } else {
            Image img = new Image(p.toUri().toString());
            if (img.isError()) throw new IOException("unreadable image", img.getException());
            Platform.runLater(() -> {
                if (reveal.getAsBoolean()) photo.view().setImage(img);
            });
        }
    }

    private static FlowPane bar(PhotoPane photo, Runnable onSave, Label counter, Button full) {
        Label percent = new Label();
        percent.getStyleClass().add("media-viewer-percent");
        percent.textProperty().bind(Bindings.format("%d %%", photo.percentProperty()));
        Button save = new Button(I18n.t("message.saveAs"));
        save.getStyleClass().add("secondary-button");
        save.setOnAction(e -> onSave.run());
        FlowPane bar = new FlowPane(6, 6,
                Icons.iconButton(Icons.MINUS, I18n.t("viewer.zoomOut"), () -> photo.change(z -> z.zoomBy(1 / PhotoPane.STEP))),
                percent,
                Icons.iconButton(Icons.PLUS, I18n.t("viewer.zoomIn"), () -> photo.change(z -> z.zoomBy(PhotoPane.STEP))),
                textButton(I18n.t("viewer.fit"), I18n.t("viewer.fitHelp"), () -> photo.change(PhotoZoom::fit)),
                textButton("100 %", I18n.t("viewer.actualSize"), () -> photo.change(PhotoZoom::actualSize)),
                Icons.iconButton(Icons.ROTATE, I18n.t("viewer.rotate"), () -> photo.change(PhotoZoom::rotate)),
                save, counter, full);
        bar.setAlignment(Pos.CENTER);
        bar.setRowValignment(javafx.geometry.VPos.CENTER);
        bar.getStyleClass().add("media-viewer-bar");
        return bar;
    }

    private static Button textButton(String text, String help, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("secondary-button");
        b.setTooltip(new Tooltip(help));
        b.setAccessibleText(help);
        b.setOnAction(e -> action.run());
        return b;
    }

    /** True if the key was one of the viewer's. By character for + and -, which move around between keyboards. */
    private static boolean key(PhotoPane photo, KeyEvent e) {
        switch (e.getCode()) {
            case LEFT -> photo.change(z -> z.pan(ARROW_STEP, 0));
            case RIGHT -> photo.change(z -> z.pan(-ARROW_STEP, 0));
            case UP -> photo.change(z -> z.pan(0, ARROW_STEP));
            case DOWN -> photo.change(z -> z.pan(0, -ARROW_STEP));
            case ADD -> photo.change(z -> z.zoomBy(PhotoPane.STEP));
            case SUBTRACT -> photo.change(z -> z.zoomBy(1 / PhotoPane.STEP));
            default -> {
                switch (e.getText().toLowerCase()) {
                    case "+", "=" -> photo.change(z -> z.zoomBy(PhotoPane.STEP));
                    case "-" -> photo.change(z -> z.zoomBy(1 / PhotoPane.STEP));
                    case "0" -> photo.change(PhotoZoom::fit);
                    case "1" -> photo.change(PhotoZoom::actualSize);
                    case "r" -> photo.change(PhotoZoom::rotate);
                    default -> { return false; }
                }
            }
        }
        return true;
    }
}
