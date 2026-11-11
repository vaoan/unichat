package app.unichat.ui.media;

import app.unichat.support.I18n;
import app.unichat.ui.FxCoalescer;
import app.unichat.ui.Icons;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;

import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleConsumer;
import java.util.function.Function;

/**
 * A picture's load as it really goes, over its box (a sticker, a photo, a tile of the panel): a ring with how much has
 * arrived (the bridge says the size, so it's the real share; spinning while that isn't known), gone once the picture is
 * drawn; and when it couldn't be downloaded or opened, a button that tries again. Nothing shows for what was already here.
 */
public final class MediaLoading {
    private MediaLoading() {}

    /**
     * Starts {@code load}, which gets where to tell its progress (0..1) and gives a future done when the picture is drawn
     * (failed when it couldn't be downloaded or decoded).
     */
    public static void track(StackPane box, Function<DoubleConsumer, CompletableFuture<?>> load) {
        ProgressRing ring = new ProgressRing();
        double[] latest = {-1};
        FxCoalescer redraw = new FxCoalescer(() -> {
            ring.setProgress(latest[0]);
            ring.setAccessibleText(latest[0] < 0 ? I18n.t("media.loading")
                    : I18n.t("media.loadingPercent", Math.round(latest[0] * 100)));
        });
        CompletableFuture<?> loading = load.apply(p -> {
            latest[0] = Math.max(0, Math.min(1, p));
            redraw.request();
        });
        if (loading.isDone() && !loading.isCompletedExceptionally()) return; // already here: no ring at all
        ring.setAccessibleText(I18n.t("media.loading"));
        StackPane.setAlignment(ring, Pos.CENTER);
        box.getChildren().add(ring);
        loading.whenComplete((v, err) -> Platform.runLater(() -> {
            box.getChildren().remove(ring);
            if (err != null) offerRetry(box, load);
        }));
    }

    /** A button over the box that loads it again; a click on it never reaches the box (which would send or open it). */
    private static void offerRetry(StackPane box, Function<DoubleConsumer, CompletableFuture<?>> load) {
        Button retry = Icons.iconButton(Icons.REPLAY, I18n.t("media.retry"), null);
        retry.getStyleClass().add("media-retry");
        Tooltip.install(retry, new Tooltip(I18n.t("media.lost")));
        retry.addEventHandler(MouseEvent.MOUSE_CLICKED, Event::consume);
        retry.setOnAction(e -> {
            box.getChildren().remove(retry);
            track(box, load);
        });
        StackPane.setAlignment(retry, Pos.CENTER);
        box.getChildren().add(retry);
    }
}
