package app.unichat.ui;

import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.image.ImageView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for "los avatares flashean": a recycled list cell that gets a chat whose photo was already loaded
 * must show it immediately, never the initials in between. Needs a display: run with -Dunichat.uiTests=true.
 */
@EnabledIfSystemProperty(named = "unichat.uiTests", matches = "true")
class AvatarFlashUiTest {

    @Test
    void recycledAvatarShowsKnownPhotoWithoutFlashing() throws Exception {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            started.await(10, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        ImageCache.clear();
        MockChatService svc = new MockChatService();
        Avatar avatar = fx(() -> new Avatar(44));
        ImageView photo = fx(() -> (ImageView) avatar.getChildren().stream().filter(n -> n instanceof ImageView).findFirst().orElseThrow());

        fx(() -> { avatar.set("Mamá", "!mama", "demo://Avatar-Mama", svc); return null; });
        long end = System.currentTimeMillis() + 10_000;
        while (!fx(photo::isVisible) && System.currentTimeMillis() < end) Thread.sleep(50);
        assertTrue(fx(photo::isVisible), "first load finishes");

        // The cell is recycled for another chat, then gets Mamá again (what every inbox refresh does).
        boolean visibleRightAway = fx(() -> {
            avatar.set("Laura", "!laura", "demo://Avatar-Laura", svc);
            avatar.set("Mamá", "!mama", "demo://Avatar-Mama", svc);
            return photo.isVisible() && photo.getImage() != null;
        });
        assertTrue(visibleRightAway, "known photo must show in the same frame (no initials flash)");
        svc.stop();
    }

    private static <T> T fx(Supplier<T> s) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(s.get());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(10, TimeUnit.SECONDS);
    }
}
