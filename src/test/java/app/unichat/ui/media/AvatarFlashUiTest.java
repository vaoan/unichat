package app.unichat.ui.media;

import app.unichat.ui.chats.Avatar;
import app.unichat.ui.FxWait;
import app.unichat.service.MockChatService;
import javafx.application.Platform;
import javafx.scene.image.ImageView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;
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
            started.await();
        } catch (IllegalStateException alreadyRunning) {
            // another UI test started the toolkit
        }
        ImageCache.clear();
        MockChatService svc = new MockChatService();
        Avatar avatar = fx(() -> new Avatar(44));
        ImageView photo = fx(() -> (ImageView) avatar.getChildren().stream().filter(n -> n instanceof ImageView).findFirst().orElseThrow());

        fx(() -> { avatar.set("Mamá", "!mama", "demo://Avatar-Mama", svc); return null; });
        FxWait.until("first load finishes", photo::isVisible);

        // The cell is recycled for another chat, then gets Mamá again (what every inbox refresh does).
        boolean visibleRightAway = fx(() -> {
            avatar.set("Laura", "!laura", "demo://Avatar-Laura", svc);
            avatar.set("Mamá", "!mama", "demo://Avatar-Mama", svc);
            return photo.isVisible() && photo.getImage() != null;
        });
        assertTrue(visibleRightAway, "known photo must show in the same frame (no initials flash)");

        // Opening the chat: header (34 px) and info panel (84 px) avatars are new nodes of other sizes.
        boolean headerRightAway = fx(() -> {
            Avatar header = new Avatar(34);
            Avatar info = new Avatar(84);
            header.set("Mamá", "!mama", "demo://Avatar-Mama", svc);
            info.set("Mamá", "!mama", "demo://Avatar-Mama", svc);
            return visiblePhoto(header) && visiblePhoto(info);
        });
        assertTrue(headerRightAway, "a photo seen in the list must show at once in the header and info panel too");
        svc.stop();
    }

    private static boolean visiblePhoto(Avatar a) {
        return a.getChildren().stream().anyMatch(n -> n instanceof ImageView v && v.isVisible() && v.getImage() != null);
    }

    private static <T> T fx(Supplier<T> s) throws Exception {
        return FxWait.fx(s::get);
    }
}
