package app.unichat.ui.chats;

import app.unichat.support.I18n;
import javafx.application.Platform;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** After a save folder changed: shows the new one and says what moved with it, or why nothing could. */
public final class FolderMoves {
    private FolderMoves() {}

    public static void tell(CompletableFuture<Integer> moving, Runnable redraw, Consumer<String> toast) {
        moving.whenComplete((moved, err) -> Platform.runLater(() -> {
            redraw.run();
            if (err != null) toast.accept(I18n.t("autosave.moveFailed", app.unichat.support.Errors.message(err)));
            else if (moved > 0) toast.accept(I18n.n("autosave.moved", moved));
        }));
    }
}
